package nl.vanvrouwerff.iptv.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.ui.channels.CategoryItem
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Programme guide: live channels down, 3.5 hours across. Opens on the favourites; the chip
 * row at the top switches to a category. OK on a programme that is on now zaps to it.
 */
@Composable
fun GuideScreen(
    onBack: () -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    vm: GuideViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val state by vm.state.collectAsState()
    BackHandler(enabled = true, onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep)
            .padding(horizontal = 48.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.guide_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color = IptvPalette.TextPrimary,
            ),
        )
        Spacer(Modifier.height(10.dp))
        if (state.loading) {
            Text(stringResource(R.string.detail_loading), color = IptvPalette.TextSecondary)
            return@Column
        }
        if (state.groups.isEmpty()) {
            Text(stringResource(R.string.channels_empty_type_tv), color = IptvPalette.TextSecondary)
            return@Column
        }
        TvLazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(state.groups, key = { _, g -> g.title }) { i, g ->
                Box(Modifier.width(200.dp)) {
                    CategoryItem(
                        label = g.title,
                        selected = i == state.groupIndex,
                        onClick = { vm.selectGroup(i) },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val group = state.group ?: return@Column
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val timelineWidth = maxWidth - CHANNEL_CELL_WIDTH
            val window = (state.toMs - state.fromMs).coerceAtLeast(1L)
            fun x(ms: Long): Dp = timelineWidth * ((ms - state.fromMs).toFloat() / window)
            Column {
                TimeRuler(fromMs = state.fromMs, toMs = state.toMs, x = ::x)
                Spacer(Modifier.height(6.dp))
                val now = System.currentTimeMillis()
                val firstFocus = remember(state.groupIndex) { FocusRequester() }
                LaunchedEffect(state.groupIndex, state.programmesByKey.isNotEmpty()) {
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { firstFocus.requestFocus() }
                }
                TvLazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    itemsIndexed(group.channels, key = { _, ch -> ch.id }) { rowIndex, ch ->
                        val programmes = ch.epgChannelId?.let { state.programmesByKey[it] }.orEmpty()
                        GuideRow(
                            channel = ch,
                            number = state.numberById[ch.id],
                            programmes = programmes,
                            now = now,
                            fromMs = state.fromMs,
                            toMs = state.toMs,
                            x = ::x,
                            timelineWidth = timelineWidth,
                            firstFocus = if (rowIndex == 0) firstFocus else null,
                            onPlay = { onPlay(ch, group.channels) },
                        )
                    }
                }
            }
            // "Now" marker across the grid.
            val nowX = x(System.currentTimeMillis())
            if (nowX > 0.dp && nowX < timelineWidth) {
                Box(
                    modifier = Modifier
                        .offset(x = CHANNEL_CELL_WIDTH + nowX)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(IptvPalette.Accent.copy(alpha = 0.8f)),
                )
            }
        }
    }
}

@Composable
private fun TimeRuler(fromMs: Long, toMs: Long, x: (Long) -> Dp) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
        var t = fromMs
        while (t < toMs) {
            Text(
                text = fmt.format(Date(t)),
                style = MaterialTheme.typography.labelSmall,
                color = IptvPalette.TextTertiary,
                modifier = Modifier.offset(x = CHANNEL_CELL_WIDTH + x(t)),
            )
            t += GuideViewModel.HALF_HOUR_MS
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    number: Int?,
    programmes: List<ProgrammeEntity>,
    now: Long,
    fromMs: Long,
    toMs: Long,
    x: (Long) -> Dp,
    timelineWidth: Dp,
    firstFocus: FocusRequester?,
    onPlay: () -> Unit,
) {
    Row(modifier = Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(CHANNEL_CELL_WIDTH).padding(end = 10.dp)) {
            Text(
                text = listOfNotNull(number?.let { "%03d".format(it) }, channel.name).joinToString("  "),
                style = MaterialTheme.typography.labelLarge,
                color = IptvPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.width(timelineWidth).fillMaxHeight()) {
            val visible = programmes.filter { it.stopMs > fromMs && it.startMs < toMs }
            if (visible.isEmpty()) {
                GuideCell(
                    title = stringResource(R.string.guide_no_data),
                    live = true,
                    modifier = Modifier
                        .width(timelineWidth)
                        .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                    onClick = onPlay,
                )
            } else {
                val liveIndex = visible.indexOfFirst { it.startMs <= now && it.stopMs > now }
                visible.forEachIndexed { i, p ->
                    val start = maxOf(p.startMs, fromMs)
                    val end = minOf(p.stopMs, toMs)
                    val live = p.startMs <= now && p.stopMs > now
                    val takesFirstFocus = firstFocus != null && i == (if (liveIndex >= 0) liveIndex else 0)
                    GuideCell(
                        title = p.title,
                        live = live,
                        modifier = Modifier
                            .offset(x = x(start))
                            .width((x(end) - x(start) - 2.dp).coerceAtLeast(8.dp))
                            .then(if (takesFirstFocus) Modifier.focusRequester(firstFocus!!) else Modifier),
                        onClick = { if (live) onPlay() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideCell(
    title: String,
    live: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) IptvPalette.SurfaceElevated else IptvPalette.SurfaceLift,
            contentColor = if (live) IptvPalette.TextPrimary else IptvPalette.TextSecondary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, focusedScale = 1f),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private val CHANNEL_CELL_WIDTH = 190.dp
private val ROW_HEIGHT = 52.dp
