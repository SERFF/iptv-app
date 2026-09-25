package nl.vanvrouwerff.iptv.ui.detail

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyListScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus

/**
 * Shared layout for the movie and series detail screens. The first list item is a header
 * of fixed height (title, meta, actions) pinned to the bottom of the backdrop, so title and
 * buttons are always fully on screen when the page opens, whatever the plot length. The
 * sections below scroll in over a solid background as the D-pad moves down.
 */
@Composable
fun DetailScaffold(
    backdropUrl: String?,
    eyebrow: String?,
    title: String,
    meta: String?,
    headerExtra: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
    sections: TvLazyListScope.() -> Unit,
) {
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

    Box(modifier = Modifier.fillMaxSize().background(IptvPalette.BackgroundDeep)) {
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(backdropScale),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(IptvPalette.AccentDeep, IptvPalette.BackgroundDeep)),
                ),
            )
        }
        // Readability scrims: dark on the left where the text sits, and a fade into the
        // page background at the bottom so the sections underneath read against black.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to IptvPalette.BackgroundDeep.copy(alpha = 0.92f),
                    0.55f to IptvPalette.BackgroundDeep.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to IptvPalette.BackgroundDeep.copy(alpha = 0.25f),
                    0.45f to Color.Transparent,
                    0.8f to IptvPalette.BackgroundDeep.copy(alpha = 0.85f),
                    1f to IptvPalette.BackgroundDeep,
                ),
            ),
        )

        TvLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item(key = "__header__") {
                Box(modifier = Modifier.fillMaxWidth().height(DETAIL_HEADER_HEIGHT)) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = DETAIL_H_PADDING, end = 32.dp, bottom = 24.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.66f)) {
                            eyebrow?.let {
                                Text(
                                    text = it.uppercase(),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = IptvPalette.TextTertiary,
                                        letterSpacing = 3.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = IptvPalette.TextPrimary,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!meta.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = IptvPalette.TextSecondary,
                                        letterSpacing = 2.sp,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            headerExtra()
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
            sections()
        }
    }
}

/** One titled block below the header ("Verhaal", "Cast", "Vergelijkbaar", …). */
fun TvLazyListScope.detailSection(
    key: String,
    title: String,
    content: @Composable () -> Unit,
) {
    item(key = key) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(IptvPalette.BackgroundDeep)
                .padding(start = DETAIL_H_PADDING, end = DETAIL_H_PADDING, top = 20.dp, bottom = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = IptvPalette.TextPrimary,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * Plot text as a focusable block: plain text can't take D-pad focus, so without this the
 * list would jump straight past the synopsis to the next focusable row.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableTextBlock(text: String, footer: List<String> = emptyList()) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = FocusStyle.Fill.copy(alpha = 0.5f),
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, focusedScale = 1f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
            )
            footer.forEach { line ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextTertiary,
                )
            }
        }
    }
}

val DETAIL_HEADER_HEIGHT = 400.dp
val DETAIL_H_PADDING = 64.dp
