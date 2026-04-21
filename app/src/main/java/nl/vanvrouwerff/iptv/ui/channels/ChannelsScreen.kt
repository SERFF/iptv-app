package nl.vanvrouwerff.iptv.ui.channels

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

/**
 * Rough threshold that distinguishes "bulk insert in progress" from "user genuinely has
 * very few channels". Tuned for the common case of a 2–20k-row Xtream catalogue; below
 * this the skeleton stays up, above it the rails appear even mid-refresh.
 */
private const val SKELETON_MIN_COUNT = 50

private data class TypeTab(val type: ContentType, val labelRes: Int, val emptyRes: Int)

private val Tabs = listOf(
    TypeTab(ContentType.TV, R.string.tab_tv, R.string.channels_empty_type_tv),
    TypeTab(ContentType.MOVIE, R.string.tab_movies, R.string.channels_empty_type_movies),
    TypeTab(ContentType.SERIES, R.string.tab_series, R.string.channels_empty_type_series),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayDirect: (Channel) -> Unit,
    vm: ChannelsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AmbientBackdrop()

            when {
                // Keep the skeleton up while the first refresh is still running. Room emits
                // mid-bulk-insert with only a handful of rows, which — combined with the
                // single-row `hasAnyChannels` flip — used to drop us into the rails view
                // with 3 channels, then flash as the real data arrived. Keeping the
                // skeleton up until the table crosses SKELETON_MIN_COUNT rows avoids that
                // flicker. We still exit via !refreshing so a genuinely-tiny playlist
                // (say 8 channels) isn't stuck on skeleton forever.
                (state.loading || state.refreshing) &&
                    state.totalChannelCount < SKELETON_MIN_COUNT ->
                    ChannelsSkeleton()

                state.error != null && !state.hasAnyChannels ->
                    ErrorState(
                        message = state.error ?: stringResource(R.string.error_generic),
                        onRetry = vm::refresh,
                        onOpenSettings = onOpenSettings,
                    )

                !state.hasAnyChannels ->
                    EmptyState(onOpenSettings = onOpenSettings, onRetry = vm::refresh)

                else -> NetflixLayout(
                    state = state,
                    onSelectType = vm::selectType,
                    onOpenSettings = onOpenSettings,
                    onOpenProfiles = onOpenProfiles,
                    onRefresh = vm::refresh,
                    onPlay = onPlay,
                    onPlayDirect = onPlayDirect,
                    onSetManaging = vm::setManagingFavorites,
                    onToggleFavorite = vm::toggleFavorite,
                    onSearchChange = vm::setSearchQuery,
                    onRememberSearch = vm::rememberCurrentSearch,
                    onClearRecents = vm::clearRecentSearches,
                )
            }
        }
    }
}

@Composable
private fun AmbientBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        IptvPalette.AccentDeep.copy(alpha = 0.28f),
                        IptvPalette.BackgroundDeep,
                    ),
                    radius = 1600f,
                )
            )
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetflixLayout(
    state: ChannelsUiState,
    onSelectType: (ContentType) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayDirect: (Channel) -> Unit,
    onSetManaging: (Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onRememberSearch: () -> Unit,
    onClearRecents: () -> Unit,
) {
    // Shared across the whole layout so BACK from anywhere inside the rails can jump
    // straight to the top (and the TopBar's tab pills) without the user having to crawl
    // back row-by-row through 60 rails of posters.
    val railsListState = androidx.tv.foundation.lazy.list.rememberTvLazyListState()
    val tabFocusRequester = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var searchVisible by remember { mutableStateOf(false) }

    // BACK while the search bar is open: close it and clear the query so we return to
    // the rails view in a clean state rather than leaving stale results behind.
    BackHandler(enabled = searchVisible) {
        searchVisible = false
        onSearchChange("")
    }

    // BACK at the base rails view: if we've scrolled below the hero, snap back to the top
    // and focus the active tab pill. When already at the top, fall through so the system
    // can handle BACK normally (e.g. exit the app).
    BackHandler(
        enabled = !state.managingFavorites &&
            !searchVisible &&
            !state.isSearching &&
            (railsListState.firstVisibleItemIndex > 0 ||
                railsListState.firstVisibleItemScrollOffset > 0),
    ) {
        scope.launch {
            runCatching { railsListState.scrollToItem(0) }
            runCatching { tabFocusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            selected = state.selectedType,
            onSelect = onSelectType,
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            onOpenSearch = { searchVisible = true },
            onRefresh = onRefresh,
            refreshing = state.refreshing,
            lastRefreshAtMs = state.lastRefreshAtMs,
            error = state.error,
            activeProfileName = state.activeProfileName,
            selectedTabFocusRequester = tabFocusRequester,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                // Re-key on the managing flag so the manage view animates in cleanly.
                targetState = state.selectedType to state.managingFavorites,
                transitionSpec = {
                    fadeIn(tween(240)) togetherWith fadeOut(tween(240))
                },
                label = "type-switch",
            ) { (type, managing) ->
                when {
                    type == ContentType.TV && managing ->
                        ManageFavoritesView(
                            state = state,
                            onDone = { onSetManaging(false) },
                            onToggleFavorite = onToggleFavorite,
                        )
                    else -> RailsView(
                        state = state,
                        railsListState = railsListState,
                        searchVisible = searchVisible,
                        onCloseSearch = {
                            searchVisible = false
                            onSearchChange("")
                        },
                        onPlay = onPlay,
                        onPlayDirect = onPlayDirect,
                        onSearchChange = onSearchChange,
                        onRememberSearch = onRememberSearch,
                        onClearRecents = onClearRecents,
                        onStartManaging = { onSetManaging(true) }.takeIf { type == ContentType.TV },
                    )
                }
            }
            KeyHintStrip(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Thin, non-focusable remote-key legend pinned to the bottom of the screen. Context-aware:
 * the hint set depends on the current tab and whether the user is in manage mode. Kept
 * deliberately compact (one row, pill background) so it doesn't compete with rails.
 */
@Composable
private fun KeyHintStrip(
    state: ChannelsUiState,
    modifier: Modifier = Modifier,
) {
    val hints: List<Int> = when {
        state.managingFavorites -> listOf(
            R.string.keyhint_ok_play, // "OK toevoegen/verwijderen" — re-use label wording
            R.string.keyhint_back_exit,
        )
        state.selectedType == ContentType.TV -> listOf(
            R.string.keyhint_ok_play,
            R.string.keyhint_menu_favorites,
            R.string.keyhint_search,
            R.string.keyhint_settings,
        )
        else -> listOf(
            R.string.keyhint_ok_play,
            R.string.keyhint_search,
            R.string.keyhint_settings,
        )
    }
    Row(
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(IptvPalette.SurfaceElevated.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = IptvPalette.TextSecondary,
                    letterSpacing = 1.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailsView(
    state: ChannelsUiState,
    railsListState: androidx.tv.foundation.lazy.list.TvLazyListState =
        androidx.tv.foundation.lazy.list.rememberTvLazyListState(),
    searchVisible: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayDirect: (Channel) -> Unit,
    onSearchChange: (String) -> Unit,
    onRememberSearch: () -> Unit = {},
    onClearRecents: () -> Unit = {},
    onStartManaging: (() -> Unit)? = null,
) {
    val rails = state.rails
    val hero = state.hero
    val emptyRes = Tabs.firstOrNull { it.type == state.selectedType }?.emptyRes
        ?: R.string.channels_empty

    Column(modifier = Modifier.fillMaxSize()) {
        if (searchVisible || onStartManaging != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchVisible) {
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = onSearchChange,
                        onClose = onCloseSearch,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (onStartManaging != null) {
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onStartManaging) {
                        Text(
                            stringResource(R.string.favorites_manage),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        if (searchVisible && !state.isSearching && state.recentSearches.isNotEmpty()) {
            RecentSearchChips(
                recents = state.recentSearches,
                onPick = onSearchChange,
                onClear = onClearRecents,
            )
        }

        when {
            searchVisible && state.isSearching -> SearchResultsView(
                query = state.searchQuery,
                results = state.searchResults,
                contentType = state.selectedType,
                onPlay = { ch, list ->
                    onRememberSearch()
                    onPlay(ch, list)
                },
            )
            rails.isEmpty() || hero == null -> CenterMessage(stringResource(emptyRes))
            else -> TvLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = railsListState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item {
                    HeroBanner(
                        channel = hero,
                        isLastWatched = hero.id == state.lastWatchedId,
                        nowPlaying = hero.epgChannelId?.let { state.nowPlayingByEpgId[it] },
                        progressFraction = state.progressById[hero.id],
                        onPlay = {
                            // Series flow through too — the outer router opens the detail
                            // screen for them instead of the player.
                            onPlay(hero, state.playableChannels)
                        },
                    )
                }
                items(rails, key = { it.title }) { rail ->
                    RailRow(
                        rail = rail,
                        contentType = state.selectedType,
                        progressById = state.progressById,
                        nowPlayingByEpgId = state.nowPlayingByEpgId,
                        onPlay = { ch ->
                            if (rail.isDirectPlay) {
                                // "Verder kijken": skip the detail screen; auto-resume.
                                onPlayDirect(ch)
                            } else {
                                val list = rail.channels.filter { it.streamUrl != null }
                                onPlay(ch, list)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bar is only composed when the user has explicitly opened it from the top bar,
    // so we grab focus and open the IME on first composition. No D-pad-traversal gating
    // is needed here (unlike the older always-visible variant): if the bar is on screen,
    // it is because the user asked to type.
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // RecognizerIntent returns the full list ranked by confidence; take the top spoken
        // phrase and feed it straight into the query so the debounced search picks it up.
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) onQueryChange(spoken)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = IptvPalette.TextTertiary,
                )
            },
            placeholder = {
                androidx.compose.material3.Text(stringResource(R.string.search_placeholder))
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
        VoiceSearchButton(
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        context.getString(R.string.search_voice),
                    )
                }
                try {
                    voiceLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    // Some Android TV boxes ship without a speech recognizer — fall back to
                    // a toast rather than crashing.
                    Toast.makeText(
                        context,
                        context.getString(R.string.search_voice_unsupported),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        Button(onClick = onClose) {
            Text(
                stringResource(R.string.search_clear),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VoiceSearchButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(R.string.icon_desc_voice),
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentSearchChips(
    recents: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.search_recent),
            style = MaterialTheme.typography.labelMedium.copy(
                color = IptvPalette.TextTertiary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            ),
        )
        TvLazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(recents, key = { it }) { q ->
                RecentChip(label = q, onClick = { onPick(q) })
            }
            item(key = "__clear__") {
                RecentChip(
                    label = stringResource(R.string.search_clear_recent),
                    subtle = true,
                    onClick = onClear,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentChip(label: String, subtle: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (subtle) Color.Transparent else IptvPalette.SurfaceElevated,
            contentColor = if (subtle) IptvPalette.TextTertiary else IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultsView(
    query: String,
    results: List<Channel>,
    contentType: ContentType,
    onPlay: (Channel, List<Channel>) -> Unit,
) {
    if (results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.search_empty, query),
                style = MaterialTheme.typography.titleMedium,
                color = IptvPalette.TextSecondary,
            )
        }
        return
    }

    val columns = if (contentType == ContentType.TV) 4 else 5
    val playable = remember(results) { results.filter { it.streamUrl != null } }

    TvLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(results.chunked(columns), key = { row -> row.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { channel ->
                    when (contentType) {
                        ContentType.TV -> LogoCard(
                            channel = channel,
                            progressFraction = null,
                            onClick = {
                                if (channel.streamUrl != null) onPlay(channel, playable)
                            },
                        )
                        ContentType.MOVIE, ContentType.SERIES -> PosterCard(
                            channel = channel,
                            progressFraction = null,
                            // Series cards have a null streamUrl but must still propagate —
                            // the outer router takes them to the detail screen.
                            onClick = { onPlay(channel, playable) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoritesView(
    state: ChannelsUiState,
    onPlay: (Channel, List<Channel>) -> Unit,
    onStartManaging: () -> Unit,
) {
    val favorites = state.favoriteChannels

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tab_tv),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = IptvPalette.TextPrimary,
                ),
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = onStartManaging) {
                Text(
                    stringResource(R.string.favorites_manage),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (favorites.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.favorites_empty_title),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = IptvPalette.TextPrimary,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.favorites_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onStartManaging) {
                    Text(
                        stringResource(R.string.favorites_manage),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        } else {
            TvLazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(favorites, key = { it.id }) { ch ->
                    ChannelListRow(
                        channel = ch,
                        trailing = null,
                        nowPlaying = ch.epgChannelId?.let { state.nowPlayingByEpgId[it] },
                        onClick = {
                            val list = favorites.filter { it.streamUrl != null }
                            if (ch.streamUrl != null) onPlay(ch, list)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageFavoritesView(
    state: ChannelsUiState,
    onDone: () -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    // Manage mode is TV-only, and `state.channels`/`state.categories` are the current
    // tab's data. When we reach this view the current tab is TV, so these *are* the TV
    // channels and categories.
    val tvChannels = state.channels
    val categories = state.categories
    // null means "Alle"; default to first real category so the user doesn't face 26k items.
    var selectedCat by remember(categories) { mutableStateOf(categories.firstOrNull()) }

    val visible = remember(selectedCat, tvChannels) {
        if (selectedCat == null) tvChannels
        else tvChannels.filter { it.groupTitle == selectedCat }
    }
    val star = stringResource(R.string.favorites_marker)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.favorites_manage),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = IptvPalette.TextPrimary,
                ),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.favorites_hint_add),
                style = MaterialTheme.typography.bodySmall,
                color = IptvPalette.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = onDone) {
                Text(
                    stringResource(R.string.favorites_done),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            TvLazyColumn(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(IptvPalette.SurfaceElevated, RoundedCornerShape(14.dp))
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "__all__") {
                    CategoryItem(
                        label = stringResource(R.string.favorites_all_categories),
                        selected = selectedCat == null,
                        onClick = { selectedCat = null },
                    )
                }
                items(categories, key = { it }) { cat ->
                    CategoryItem(
                        label = cat,
                        selected = selectedCat == cat,
                        onClick = { selectedCat = cat },
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.channels_empty_type_tv),
                        style = MaterialTheme.typography.titleMedium,
                        color = IptvPalette.TextSecondary,
                    )
                }
            } else {
                TvLazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    items(visible, key = { it.id }) { ch ->
                        val isFav = ch.id in state.favoriteIds
                        ChannelListRow(
                            channel = ch,
                            trailing = if (isFav) star else null,
                            onClick = { onToggleFavorite(ch.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelListRow(
    channel: Channel,
    trailing: String?,
    nowPlaying: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.SurfaceLift,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            // Tall dense list: a hair of scale + a left accent bar + a 2dp outline give
            // the row unambiguous focus from 3m without displacing neighbours.
            .then(
                if (focused)
                    Modifier.border(
                        width = 2.dp,
                        brush = Brush.horizontalGradient(
                            listOf(IptvPalette.Accent, IptvPalette.AccentSoft),
                        ),
                        shape = shape,
                    )
                else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                nowPlaying?.let {
                    Text(
                        text = stringResource(R.string.epg_now_prefix, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = IptvPalette.AccentSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            channel.groupTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = IptvPalette.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.titleMedium,
                    color = IptvPalette.Accent,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else Color.Transparent,
            contentColor = if (selected) Color.White else IptvPalette.TextSecondary,
            focusedContainerColor = if (selected) IptvPalette.Accent else IptvPalette.SurfaceLift,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                // Accent outline only when focused AND not already the selected row —
                // the selected row already has the accent fill, a second outline would be noisy.
                if (focused && !selected)
                    Modifier.border(2.dp, IptvPalette.Accent, shape)
                else Modifier,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBar(
    selected: ContentType,
    onSelect: (ContentType) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    lastRefreshAtMs: Long,
    error: String?,
    activeProfileName: String?,
    selectedTabFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Black,
                color = IptvPalette.Accent,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.width(36.dp))
        Tabs.forEach { tab ->
            val isSelected = tab.type == selected
            TabPill(
                label = stringResource(tab.labelRes),
                selected = isSelected,
                onClick = { onSelect(tab.type) },
                focusRequester = if (isSelected) selectedTabFocusRequester else null,
            )
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        SourceStatusPill(
            refreshing = refreshing,
            lastRefreshAtMs = lastRefreshAtMs,
            error = error,
            onClick = onRefresh,
        )
        Spacer(Modifier.width(12.dp))
        SearchChip(onClick = onOpenSearch)
        Spacer(Modifier.width(12.dp))
        if (activeProfileName != null) {
            ProfileChip(name = activeProfileName, onClick = onOpenProfiles)
            Spacer(Modifier.width(12.dp))
        }
        SettingsChip(onClick = onOpenSettings)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceStatusPill(
    refreshing: Boolean,
    lastRefreshAtMs: Long,
    error: String?,
    onClick: () -> Unit,
) {
    val (label, color) = when {
        refreshing -> stringResource(R.string.status_refreshing) to IptvPalette.TextSecondary
        error != null && lastRefreshAtMs == 0L ->
            stringResource(R.string.status_error) to IptvPalette.Accent
        lastRefreshAtMs == 0L -> stringResource(R.string.status_never) to IptvPalette.TextTertiary
        else -> {
            val ageMs = System.currentTimeMillis() - lastRefreshAtMs
            val ageLabel = humanDuration(ageMs)
            if (ageMs > STALE_THRESHOLD_MS) {
                stringResource(R.string.status_stale, ageLabel) to IptvPalette.AccentSoft
            } else {
                stringResource(R.string.status_ok, ageLabel) to IptvPalette.TextSecondary
            }
        }
    }
    Surface(
        onClick = { if (!refreshing) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.6f),
            contentColor = color,
            focusedContainerColor = IptvPalette.SurfaceElevated,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (refreshing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = IptvPalette.Accent,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileChip(name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profile-scale",
    )
    // Initial letter rendered in the accent color as a tiny avatar circle.
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.6f),
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.SurfaceElevated,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(IptvPalette.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun humanDuration(ms: Long): String {
    val abs = ms.coerceAtLeast(0)
    val minutes = abs / 60_000
    val hours = abs / 3_600_000
    val days = abs / 86_400_000
    return when {
        minutes < 1 -> stringResource(R.string.duration_just_now)
        minutes < 60 -> stringResource(R.string.duration_minutes_ago, minutes.toInt())
        hours < 24 -> stringResource(R.string.duration_hours_ago, hours.toInt())
        else -> stringResource(R.string.duration_days_ago, days.toInt())
    }
}

// Older than this and we show the "stale" variant instead of "ok".
private const val STALE_THRESHOLD_MS: Long = 24L * 3_600_000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tab-scale",
    )
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
        modifier = Modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchChip(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "search-scale",
    )
    // A single icon on a transparent pill reads as grey-on-grey against the ambient
    // gradient; a filled pill with a bright tint makes it unambiguously a button.
    // Tint on Icon is set explicitly — TV Surface's contentColor doesn't always
    // propagate through LocalContentColor to child Icons.
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.6f),
            contentColor = Color.White,
            focusedContainerColor = IptvPalette.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = stringResource(R.string.icon_desc_search),
            tint = Color.White,
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsChip(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "settings-scale",
    )
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.SurfaceElevated,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Text(
            text = stringResource(R.string.channels_settings_hint),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroBanner(
    channel: Channel,
    isLastWatched: Boolean,
    nowPlaying: String?,
    progressFraction: Float?,
    onPlay: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Focus only the FIRST time the hero appears in this composition — otherwise any change
    // to `hero` (e.g. lastWatched updates while scrolling a rail) yanks focus back to Play.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Returning from the player: re-grab focus on the Play button so the D-pad has a clear
    // landing spot. Without this the user lands somewhere unpredictable (often the top-bar)
    // after the PlayerActivity finishes and MainActivity resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                runCatching { focusRequester.requestFocus() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .padding(horizontal = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(IptvPalette.SurfaceLift),
    ) {
        val isPoster = channel.type != ContentType.TV
        val logo = channel.logoUrl

        if (isPoster && logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(IptvPalette.AccentDeep, IptvPalette.SurfaceLift),
                        )
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(end = 96.dp)
                            .size(width = 360.dp, height = 220.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to IptvPalette.BackgroundDeep.copy(alpha = 0.95f),
                        0.55f to IptvPalette.BackgroundDeep.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.75f to Color.Transparent,
                        1f to IptvPalette.BackgroundDeep.copy(alpha = 0.6f),
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.55f)
                .padding(start = 48.dp, top = 56.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Status pills — show at most one so we don't stack too much chrome over the art.
            when {
                isLastWatched -> {
                    AccentPill(text = stringResource(R.string.channels_last_watched))
                    Spacer(Modifier.height(16.dp))
                }
                channel.type == ContentType.TV && !nowPlaying.isNullOrBlank() -> {
                    NowOnPill(title = nowPlaying)
                    Spacer(Modifier.height(16.dp))
                }
                else -> Unit
            }
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (progressFraction != null && progressFraction > 0f) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(IptvPalette.SurfaceElevated),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .background(IptvPalette.Accent),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onPlay,
                modifier = Modifier.focusRequester(focusRequester),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 10.dp).size(22.dp),
                )
                Text(
                    text = heroCtaLabel(channel.type, progressFraction),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun heroCtaLabel(type: ContentType, progressFraction: Float?): String = when {
    // A resumable title deserves a different CTA — Netflix-style "Hervatten". Once the
    // fraction crosses the watched threshold we show "Afspelen" so the user can rewatch
    // from the start instead of jumping to credits.
    type == ContentType.MOVIE &&
        progressFraction != null &&
        progressFraction in 0.001f..0.95f ->
        stringResource(R.string.detail_resume)
    type == ContentType.SERIES -> stringResource(R.string.hero_view_series)
    else -> stringResource(R.string.channels_play)
}

@Composable
private fun NowOnPill(title: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(IptvPalette.SurfaceElevated.copy(alpha = 0.85f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(IptvPalette.Accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.hero_now_on_prefix, title),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AccentPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(IptvPalette.Accent)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailRow(
    rail: Rail,
    contentType: ContentType,
    progressById: Map<String, Float>,
    nowPlayingByEpgId: Map<String, String> = emptyMap(),
    onPlay: (Channel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thin accent tick — gives the rail-title row a bit of hierarchy without
            // committing to full left-bar treatment per rail.
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(IptvPalette.Accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = rail.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = IptvPalette.TextPrimary,
                ),
            )
        }
        TvLazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(rail.channels, key = { it.id }) { channel ->
                val progress = progressById[channel.id]
                when (contentType) {
                    ContentType.TV -> LogoCard(
                        channel = channel,
                        progressFraction = progress,
                        nowPlaying = channel.epgChannelId?.let { nowPlayingByEpgId[it] },
                        onClick = { onPlay(channel) },
                    )
                    ContentType.MOVIE, ContentType.SERIES ->
                        PosterCard(
                            channel = channel,
                            progressFraction = progress,
                            onClick = { onPlay(channel) },
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogoCard(
    channel: Channel,
    progressFraction: Float?,
    nowPlaying: String? = null,
    onClick: () -> Unit,
) {
    FocusableCard(width = 220.dp, height = 124.dp, onClick = onClick) { focused ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(IptvPalette.SurfaceElevated, IptvPalette.SurfaceLift),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize(),
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Black,
                        color = IptvPalette.TextSecondary,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to IptvPalette.BackgroundDeep.copy(alpha = 0.92f),
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) Color.White else IptvPalette.TextPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!nowPlaying.isNullOrBlank()) {
                    Text(
                        text = nowPlaying,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = IptvPalette.AccentSoft,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (progressFraction != null && progressFraction > 0f) {
                CardProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PosterCard(
    channel: Channel,
    progressFraction: Float?,
    onClick: () -> Unit,
) {
    FocusableCard(width = 168.dp, height = 252.dp, onClick = onClick) { focused ->
        Box(modifier = Modifier.fillMaxSize().background(IptvPalette.SurfaceElevated)) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(IptvPalette.AccentDeep, IptvPalette.SurfaceLift),
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = Color.White.copy(alpha = 0.85f),
                        ),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.6f to Color.Transparent,
                            1f to IptvPalette.BackgroundDeep.copy(alpha = 0.92f),
                        )
                    )
            )
            Text(
                text = channel.name,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (focused) Color.White else IptvPalette.TextPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (progressFraction != null && progressFraction > 0f) {
                CardProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

/**
 * Netflix-style 3dp accent bar across the bottom edge of a card, signalling saved watch
 * progress. The unfilled remainder stays dim so the bar reads as progress, not as a
 * decorative stripe.
 */
@Composable
private fun CardProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(IptvPalette.SurfaceLift.copy(alpha = 0.7f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(IptvPalette.Accent),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusableCard(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "card-scale",
    )
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceLift,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.SurfaceLift,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .size(width = width, height = height)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused)
                    Modifier.border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            listOf(IptvPalette.Accent, IptvPalette.AccentSoft),
                        ),
                        shape = shape,
                    )
                else Modifier,
            ),
    ) {
        content(focused)
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = IptvPalette.TextSecondary)
    }
}

/**
 * Placeholder layout shown while the initial refresh fills Room. Mirrors the real Netflix
 * layout (hero + poster rails) so the page doesn't visually jump when content arrives.
 * A slow alpha pulse provides just enough motion to feel alive without distracting.
 */
@Composable
private fun ChannelsSkeleton() {
    val infinite = rememberInfiniteTransition(label = "skeleton")
    val alpha by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val block = IptvPalette.SurfaceElevated.copy(alpha = alpha)

    Column(modifier = Modifier.fillMaxSize()) {
        // Top-bar placeholder (title + tab pills).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBlock(width = 120.dp, height = 22.dp, color = block)
            Spacer(Modifier.width(36.dp))
            repeat(3) {
                SkeletonBlock(width = 92.dp, height = 34.dp, shape = RoundedCornerShape(999.dp), color = block)
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            SkeletonBlock(width = 140.dp, height = 24.dp, shape = RoundedCornerShape(999.dp), color = block)
        }

        // Hero banner placeholder.
        SkeletonBlock(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            width = null,
            height = 360.dp,
            shape = RoundedCornerShape(24.dp),
            color = block,
        )

        Spacer(Modifier.height(28.dp))

        // Two rail rows so the eye understands what's coming.
        repeat(2) { rail ->
            Column(modifier = Modifier.fillMaxWidth()) {
                SkeletonBlock(
                    modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
                    width = 180.dp,
                    height = 18.dp,
                    color = block,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(5) {
                        SkeletonBlock(
                            width = 168.dp,
                            height = 252.dp,
                            shape = RoundedCornerShape(14.dp),
                            color = block,
                        )
                    }
                }
            }
            if (rail == 0) Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SkeletonBlock(
    width: Dp?,
    height: Dp,
    color: Color,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    modifier: Modifier = Modifier,
) {
    val baseModifier = if (width != null) modifier.size(width = width, height = height)
    else modifier.height(height)
    Box(modifier = baseModifier.clip(shape).background(color))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyState(onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.channels_empty),
            style = MaterialTheme.typography.titleLarge,
            color = IptvPalette.TextPrimary,
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRetry) { Text(stringResource(R.string.channels_refresh)) }
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.channels_open_settings)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.error_generic),
            style = MaterialTheme.typography.titleLarge,
            color = IptvPalette.TextPrimary,
        )
        Text(
            message,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = IptvPalette.TextSecondary,
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.channels_open_settings)) }
        }
    }
}
