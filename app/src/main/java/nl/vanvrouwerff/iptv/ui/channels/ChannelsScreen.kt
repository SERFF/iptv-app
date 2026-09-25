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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.alpha
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
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.tmdb.TmdbCatalogueMatcher
import nl.vanvrouwerff.iptv.data.tmdb.TmdbClient
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus

/**
 * Rough threshold that distinguishes "bulk insert in progress" from "user genuinely has
 * very few channels". Tuned for the common case of a 2–20k-row Xtream catalogue; below
 * this the skeleton stays up, above it the rails appear even mid-refresh.
 */
private const val SKELETON_MIN_COUNT = 50

/** How long each hero slot holds before the carousel rotates to the next one. */
private const val HERO_ROTATE_MS: Long = 9_000L

/** Full cycle for the Ken-Burns zoom — long enough to feel stately, not hypnotic. */
private const val KEN_BURNS_CYCLE_MS: Int = 12_000

/** Idle delay before a MOVIE hero swaps its Ken Burns backdrop for a muted YouTube trailer. */
private const val HERO_TRAILER_DELAY_MS: Long = 2_500L

/** Leaves the title of the first rail and the top of its cards visible on a 540dp screen. */
private val HERO_HEIGHT = 340.dp

private const val HERO_SWAP_FOCUS_DELAY_MS: Long = 650L

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
    onOpenCategories: (ContentType, String?) -> Unit = { _, _ -> },
    onOpenGuide: () -> Unit = {},
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayDirect: (Channel) -> Unit,
    onOpenDetail: (Channel) -> Unit,
    vm: ChannelsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val hoverChannel by vm.settledHoverChannel.collectAsState()

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
                    ChannelsSkeleton(progress = state.importProgress)

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
                    hoverChannel = hoverChannel,
                    focusMemoryFor = vm::focusMemoryFor,
                    onRememberFocus = vm::rememberFocus,
                    onSelectType = vm::selectType,
                    onOpenCategories = onOpenCategories,
                    onOpenGuide = onOpenGuide,
                    onOpenSettings = onOpenSettings,
                    onOpenProfiles = onOpenProfiles,
                    onRefresh = vm::refresh,
                    onPlay = onPlay,
                    onPlayDirect = onPlayDirect,
                    onOpenDetail = onOpenDetail,
                    onHover = vm::onHoverChannel,
                    onSetManaging = vm::setManagingFavorites,
                    onRemoveFromContinue = vm::removeFromContinueWatching,
                    onMarkWatched = vm::markWatched,
                    onToggleFavorite = vm::toggleFavorite,
                    onMoveFavorite = vm::moveFavorite,
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
    hoverChannel: Channel?,
    focusMemoryFor: (ContentType) -> RailFocusMemory?,
    onRememberFocus: (ContentType, RailFocusMemory?) -> Unit,
    onSelectType: (ContentType) -> Unit,
    onOpenCategories: (ContentType, String?) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayDirect: (Channel) -> Unit,
    onOpenDetail: (Channel) -> Unit,
    onHover: (Channel?) -> Unit,
    onSetManaging: (Boolean) -> Unit,
    onRemoveFromContinue: (String) -> Unit,
    onMarkWatched: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMoveFavorite: (String, Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onRememberSearch: () -> Unit,
    onClearRecents: () -> Unit,
) {
    // Shared across the whole layout so BACK from anywhere inside the rails can jump
    // straight to the top (and the TopBar's tab pills) without the user having to crawl
    // back row-by-row through 60 rails of posters.
    // Focus restore: only when this layout is freshly composed (coming back from a detail
    // screen) and only for the tab that was active then. A tab switch or cold start (no
    // memory) lands on the hero as before.
    val initialType = remember { state.selectedType }
    val initialMemory = remember { focusMemoryFor(state.selectedType) }
    val railsListState = remember(state.selectedType) {
        val m = initialMemory?.takeIf { state.selectedType == initialType }
        androidx.tv.foundation.lazy.list.TvLazyListState(m?.listIndex ?: 0, m?.listOffset ?: 0)
    }
    val focusController = remember(state.selectedType) {
        RailFocusController(
            type = state.selectedType,
            initial = initialMemory?.takeIf { state.selectedType == initialType },
            listState = railsListState,
            onRemember = onRememberFocus,
        )
    }
    val tabFocusRequester = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var searchVisible by remember { mutableStateOf(false) }
    var contextTarget by remember { mutableStateOf<Channel?>(null) }
    // Tracked via onFocusChanged on the TopBar wrapper — lets us fall BACK through to the
    // system (exit app) only when focus is already on the tabs, instead of bouncing the
    // user between tabs and exit on every press.
    var topBarHasFocus by remember { mutableStateOf(false) }

    // BACK while the search bar is open: close it and clear the query so we return to
    // the rails view in a clean state rather than leaving stale results behind.
    BackHandler(enabled = searchVisible) {
        searchVisible = false
        onSearchChange("")
    }

    // BACK anywhere inside the rails (hero, posters, any scroll position): snap the list
    // to the top and hand focus to the active tab pill so the user can immediately switch
    // between TV / films / series. Only falls through (exit app) when focus is already on
    // the TopBar.
    BackHandler(
        enabled = !state.managingFavorites &&
            !searchVisible &&
            !state.isSearching &&
            !topBarHasFocus,
    ) {
        scope.launch {
            runCatching { railsListState.scrollToItem(0) }
            runCatching { tabFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                if (searchVisible || state.managingFavorites || contextTarget != null) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MENU -> {
                        val card = focusController.focusedCard
                        when {
                            card != null -> contextTarget = card
                            state.selectedType == ContentType.TV -> onSetManaging(true)
                            else -> onOpenSettings()
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_SETTINGS -> {
                        onOpenSettings()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_SEARCH,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        searchVisible = true
                        true
                    }
                    else -> false
                }
            },
    ) {
        TopBar(
            selected = state.selectedType,
            onSelect = onSelectType,
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            onOpenSearch = { searchVisible = true },
            onOpenCategories = { onOpenCategories(state.selectedType, null) },
            onOpenGuide = onOpenGuide.takeIf { state.selectedType == ContentType.TV },
            onRefresh = onRefresh,
            refreshing = state.refreshing,
            lastRefreshAtMs = state.lastRefreshAtMs,
            error = state.error,
            activeProfileName = state.activeProfileName,
            activeProfileColorArgb = state.activeProfileColorArgb,
            activeProfileEmoji = state.activeProfileEmoji,
            selectedTabFocusRequester = tabFocusRequester,
            onFocusChanged = { topBarHasFocus = it },
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
                            onMoveFavorite = onMoveFavorite,
                        )
                    else -> RailsView(
                        state = state,
                        railsListState = railsListState,
                        focusController = focusController,
                        hoverChannel = hoverChannel,
                        searchVisible = searchVisible,
                        onCloseSearch = {
                            searchVisible = false
                            onSearchChange("")
                        },
                        onPlay = onPlay,
                        onPlayDirect = onPlayDirect,
                        onOpenDetail = onOpenDetail,
                        onHover = onHover,
                        onSearchChange = onSearchChange,
                        onRememberSearch = onRememberSearch,
                        onClearRecents = onClearRecents,
                        onStartManaging = { onSetManaging(true) }.takeIf { type == ContentType.TV },
                        onSeeAll = { category -> onOpenCategories(type, category) },
                        onContextMenu = { contextTarget = it },
                    )
                }
            }
            KeyHintStrip(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            contextTarget?.let { target ->
                val close = {
                    contextTarget = null
                    focusController.refocus(target.id)
                }
                BackHandler(enabled = true, onBack = close)
                CardContextMenu(
                    channel = target,
                    isFavorite = target.id in state.favoriteIds,
                    hasProgress = (state.progressById[target.id] ?: 0f) > 0f,
                    onToggleFavorite = {
                        onToggleFavorite(target.id)
                        close()
                    },
                    onRemoveFromContinue = {
                        onRemoveFromContinue(target.id)
                        close()
                    },
                    onMarkWatched = {
                        onMarkWatched(target.id)
                        close()
                    },
                    onManageChannels = {
                        contextTarget = null
                        onSetManaging(true)
                    },
                    onDismiss = close,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
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
            R.string.keyhint_ok_toggle_favorite,
            R.string.keyhint_back_exit,
        )
        state.selectedType == ContentType.TV -> listOf(
            R.string.keyhint_ok_play,
            R.string.keyhint_menu_favorites,
            R.string.keyhint_search,
        )
        else -> listOf(
            R.string.keyhint_ok_play,
            R.string.keyhint_search,
            R.string.keyhint_menu_settings,
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
    focusController: RailFocusController? = null,
    hoverChannel: Channel? = null,
    searchVisible: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onPlay: (Channel, List<Channel>) -> Unit,
    onPlayDirect: (Channel) -> Unit,
    onOpenDetail: (Channel) -> Unit = {},
    onHover: (Channel?) -> Unit = {},
    onSearchChange: (String) -> Unit,
    onRememberSearch: () -> Unit = {},
    onClearRecents: () -> Unit = {},
    onStartManaging: (() -> Unit)? = null,
    onSeeAll: (String) -> Unit = {},
    onContextMenu: (Channel) -> Unit = {},
) {
    val rails = state.rails
    val hero = state.hero
    // Carousel when we have multiple featured items (Movies/Series tab), single hero
    // when the selected tab only offered one candidate (TV, or an empty match).
    val heroes = state.heroes.ifEmpty { listOfNotNull(hero) }
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
                onPlay = { ch, list ->
                    onRememberSearch()
                    onPlay(ch, list)
                },
            )
            rails.isEmpty() || hero == null -> CenterMessage(stringResource(emptyRes))
            else -> {
                val leadingTitles = setOf(
                    ChannelsUiState.CONTINUE_WATCHING,
                    ChannelsUiState.POPULAR_NOW,
                    ChannelsUiState.MY_LIST,
                )
                val leadingRails = rails.filter { it.title in leadingTitles }
                val categoryRails = rails.filter { it.title !in leadingTitles }
                TvLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = railsListState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item {
                    HeroCarousel(
                        heroes = heroes,
                        isAtTop = { railsListState.firstVisibleItemIndex == 0 },
                        focusController = focusController,
                        hoverChannel = hoverChannel,
                        trailersEnabled = state.trailersAutoplay,
                        lastWatchedId = state.lastWatchedId,
                        nowPlayingByEpgId = state.nowPlayingByEpgId,
                        progressById = state.progressById,
                        onPlay = { ch ->
                            when (ch.type) {
                                ContentType.TV -> onPlay(ch, state.playableChannels)
                                ContentType.MOVIE -> onPlayDirect(ch)
                                ContentType.SERIES -> onOpenDetail(ch)
                            }
                        },
                        onMoreInfo = onOpenDetail,
                    )
                }
                items(leadingRails, key = { it.title }) { rail ->
                    RailRow(
                        rail = rail,
                        focusController = focusController,
                        onContextMenu = onContextMenu,
                        contentType = state.selectedType,
                        progressById = state.progressById,
                        onHover = onHover,
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
                if (state.recentlyAdded.isNotEmpty() && state.selectedType != ContentType.TV) {
                    item(key = "rail_recently_added") {
                        RailRow(
                            rail = Rail(
                                title = stringResource(R.string.rail_recently_added),
                                channels = state.recentlyAdded,
                            ),
                            focusController = focusController,
                            onContextMenu = onContextMenu,
                            contentType = state.selectedType,
                            progressById = state.progressById,
                            onHover = onHover,
                            onPlay = { ch -> onPlay(ch, state.recentlyAdded) },
                        )
                    }
                }
                items(categoryRails, key = { it.title }) { rail ->
                    RailRow(
                        rail = rail,
                        onSeeAll = { onSeeAll(rail.title) },
                        focusController = focusController,
                        onContextMenu = onContextMenu,
                        contentType = state.selectedType,
                        progressById = state.progressById,
                        onHover = onHover,
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

    val sections = remember(results) {
        listOf(ContentType.TV, ContentType.MOVIE, ContentType.SERIES)
            .map { type -> type to results.filter { it.type == type } }
            .filter { it.second.isNotEmpty() }
    }

    TvLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        sections.forEach { (type, sectionItems) ->
            val columns = if (type == ContentType.TV) 4 else 5
            val playable = sectionItems.filter { it.streamUrl != null }
            item(key = "header_${type.name}") {
                Text(
                    text = stringResource(
                        when (type) {
                            ContentType.TV -> R.string.search_section_tv
                            ContentType.MOVIE -> R.string.search_section_movies
                            ContentType.SERIES -> R.string.search_section_series
                        },
                        sectionItems.size,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = IptvPalette.TextPrimary,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(sectionItems.chunked(columns), key = { row -> "${type.name}_${row.first().id}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { channel ->
                        when (type) {
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
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageFavoritesView(
    state: ChannelsUiState,
    onDone: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMoveFavorite: (String, Int) -> Unit = { _, _ -> },
) {
    var orderMode by remember { mutableStateOf(false) }
    var movingId by remember { mutableStateOf<String?>(null) }
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
                stringResource(if (orderMode) R.string.favorites_hint_order else R.string.favorites_hint_add),
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
                item(key = "__order__") {
                    CategoryItem(
                        label = stringResource(R.string.favorites_order),
                        selected = orderMode,
                        onClick = {
                            orderMode = true
                            movingId = null
                        },
                    )
                }
                item(key = "__all__") {
                    CategoryItem(
                        label = stringResource(R.string.favorites_all_categories),
                        selected = !orderMode && selectedCat == null,
                        onClick = {
                            orderMode = false
                            selectedCat = null
                        },
                    )
                }
                items(categories, key = { it }) { cat ->
                    CategoryItem(
                        label = cat,
                        selected = !orderMode && selectedCat == cat,
                        onClick = {
                            orderMode = false
                            selectedCat = cat
                        },
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            if (orderMode) {
                val favs = remember(state.favoriteIds, tvChannels) {
                    inFavoriteOrder(tvChannels, state.favoriteIds)
                }
                if (favs.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.favorites_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = IptvPalette.TextSecondary,
                        )
                    }
                } else {
                    val orderListState = androidx.tv.foundation.lazy.list.rememberTvLazyListState()
                    TvLazyColumn(
                        state = orderListState,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        itemsIndexed(favs, key = { _, ch -> ch.id }) { index, ch ->
                            val moving = movingId == ch.id
                            Box(
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (!moving || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.nativeKeyEvent.keyCode) {
                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                            if (index > 0) onMoveFavorite(ch.id, -1)
                                            true
                                        }
                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            if (index < favs.lastIndex) onMoveFavorite(ch.id, +1)
                                            true
                                        }
                                        android.view.KeyEvent.KEYCODE_BACK -> {
                                            movingId = null
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            ) {
                                ChannelListRow(
                                    channel = ch,
                                    trailing = if (moving) "\u2195" else "${index + 1}",
                                    onClick = { movingId = if (moving) null else ch.id },
                                )
                            }
                        }
                    }
                }
            } else if (visible.isEmpty()) {
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
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
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
internal fun CategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
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
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
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
    onOpenCategories: () -> Unit,
    onOpenGuide: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    lastRefreshAtMs: Long,
    error: String?,
    activeProfileName: String?,
    activeProfileColorArgb: Int?,
    activeProfileEmoji: String?,
    selectedTabFocusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 20.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) },
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
        TabPill(
            label = stringResource(R.string.categories_open),
            selected = false,
            onClick = onOpenCategories,
        )
        if (onOpenGuide != null) {
            Spacer(Modifier.width(8.dp))
            TabPill(
                label = stringResource(R.string.guide_open),
                selected = false,
                onClick = onOpenGuide,
            )
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
            ProfileChip(
                name = activeProfileName,
                colorArgb = activeProfileColorArgb,
                emoji = activeProfileEmoji,
                onClick = onOpenProfiles,
            )
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
private fun ProfileChip(name: String, colorArgb: Int?, emoji: String?, onClick: () -> Unit) {
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
                    .background(colorArgb?.let { Color(it) } ?: IptvPalette.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emoji ?: name.take(1).uppercase(),
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
            .tvFocus(focused, shape, FocusStyle.ChipScale)
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
private fun HeroCarousel(
    heroes: List<Channel>,
    isAtTop: () -> Boolean,
    focusController: RailFocusController? = null,
    hoverChannel: Channel? = null,
    trailersEnabled: Boolean = true,
    lastWatchedId: String?,
    nowPlayingByEpgId: Map<String, String>,
    progressById: Map<String, Float>,
    onPlay: (Channel) -> Unit,
    onMoreInfo: (Channel) -> Unit,
) {
    if (heroes.isEmpty()) return
    // Single focus requester shared across rotations so the Play button keeps focus.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (focusController?.hasPendingRestore() == true) {
            // A rail card is about to reclaim focus. If it never shows up (catalogue
            // changed meanwhile), fall back to the hero so focus isn't lost.
            kotlinx.coroutines.delay(RESTORE_FALLBACK_MS)
            if (focusController.hasPendingRestore()) {
                focusController.abandonRestore()
                runCatching { focusRequester.requestFocus() }
            }
        } else {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Returning from the player: re-grab focus on the Play button so the D-pad has a clear
    // landing spot. Without this the user lands somewhere unpredictable (often the top-bar)
    // after the PlayerActivity finishes and MainActivity resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Only when the user is at the top — deeper in the rails, grabbing the hero
            // would scroll them away from where they were.
            if (event == Lifecycle.Event.ON_RESUME && isAtTop()) {
                runCatching { focusRequester.requestFocus() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var index by remember(heroes) { mutableStateOf(0) }
    var manualStep by remember { mutableStateOf(0) }
    val step: (Int) -> Unit = { delta ->
        index = ((index + delta) % heroes.size + heroes.size) % heroes.size
        manualStep++
    }
    // After a manual step the old banner (and its focused Play button) leaves the
    // composition; hand focus to the new banner's Play button once the swap has settled.
    LaunchedEffect(manualStep) {
        if (manualStep > 0) {
            kotlinx.coroutines.delay(HERO_SWAP_FOCUS_DELAY_MS)
            runCatching { focusRequester.requestFocus() }
        }
    }
    // Pause while the hero holds focus: rotating swaps the focused Play button out of the
    // composition (focus jumps away) and OK could start a title the user didn't see.
    var heroFocused by remember { mutableStateOf(false) }
    if (heroes.size > 1 && !heroFocused) {
        LaunchedEffect(heroes) {
            while (true) {
                kotlinx.coroutines.delay(HERO_ROTATE_MS)
                index = (index + 1) % heroes.size
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            heroFocused = it.hasFocus
            if (it.hasFocus) focusController?.onHeroFocused()
        },
    ) {
        AnimatedContent(
            targetState = index.coerceIn(0, heroes.lastIndex),
            transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) },
            label = "hero-carousel",
        ) { i ->
            val channel = heroes[i]
            HeroBanner(
                channel = channel,
                isLastWatched = channel.id == lastWatchedId,
                nowPlaying = channel.epgChannelId?.let { nowPlayingByEpgId[it] },
                progressFraction = progressById[channel.id],
                focusRequester = focusRequester,
                hoverChannel = hoverChannel,
                trailersEnabled = trailersEnabled,
                onPrev = if (heroes.size > 1) ({ step(-1) }) else null,
                onNext = if (heroes.size > 1) ({ step(+1) }) else null,
                onPlay = { onPlay(channel) },
                onMoreInfo = { onMoreInfo(channel) },
            )
        }
        if (heroes.size > 1) {
            HeroDots(
                total = heroes.size,
                current = index.coerceIn(0, heroes.lastIndex),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun HeroDots(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .size(width = if (active) 20.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (active) IptvPalette.Accent
                        else IptvPalette.TextSecondary.copy(alpha = 0.45f),
                    ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroBanner(
    channel: Channel,
    isLastWatched: Boolean,
    nowPlaying: String?,
    progressFraction: Float?,
    focusRequester: FocusRequester? = null,
    hoverChannel: Channel? = null,
    trailersEnabled: Boolean = true,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit = {},
) {
    // Single-hero fallback path (TV tab): create a local requester + focus on first
    // composition so the original TV-tab behaviour (focus the Play button) still works.
    val localFocus = remember { FocusRequester() }
    val activeFocus = focusRequester ?: localFocus
    LaunchedEffect(focusRequester == null) {
        if (focusRequester == null) runCatching { localFocus.requestFocus() }
    }

    if (channel.type == ContentType.TV) {
        CompactTvHero(
            channel = channel,
            isLastWatched = isLastWatched,
            nowPlaying = nowPlaying,
            focusRequester = activeFocus,
            onPlay = onPlay,
        )
        return
    }

    // Ken Burns — a slow, subtle scale that loops forever. The backdrop never sits still,
    // giving the banner a cinematic feel without drifting enough to distract.
    val infinite = rememberInfiniteTransition(label = "ken-burns")
    val kenBurnsScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(KEN_BURNS_CYCLE_MS, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ken-burns-scale",
    )

    // Trailer state — after a short idle window on a MOVIE hero we crossfade the Ken Burns
    // backdrop into a muted YouTube trailer. Falls back silently if TMDB has no key or the
    // YouTube player errors out.
    var trailerKey by remember(channel.id) { mutableStateOf<String?>(null) }
    var trailerActive by remember(channel.id) { mutableStateOf(false) }
    var trailerMuted by remember(channel.id) { mutableStateOf(true) }
    var backdropUrl by remember(channel.id) { mutableStateOf<String?>(null) }
    if (channel.type == ContentType.MOVIE && TmdbClient.isConfigured) {
        LaunchedEffect(channel.id, trailersEnabled) {
            val bundle = lookupHeroBundle(channel)
            backdropUrl = bundle?.backdropUrl
            if (!trailersEnabled) {
                trailerActive = false
                return@LaunchedEffect
            }
            val key = bundle?.trailerYoutubeKey ?: return@LaunchedEffect
            trailerKey = key
            kotlinx.coroutines.delay(HERO_TRAILER_DELAY_MS)
            trailerActive = true
        }
    }

    // Focused rail card repaints the hero background (debounced upstream).
    var hoverBackdrop by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hoverChannel?.id) {
        val hover = hoverChannel
        hoverBackdrop = when {
            hover == null || hover.id == channel.id -> null
            hover.type == ContentType.MOVIE && TmdbClient.isConfigured ->
                lookupHeroBundle(hover)?.backdropUrl ?: hover.logoUrl
            hover.type == ContentType.SERIES -> hover.logoUrl
            else -> null
        }
    }
    val trailerAlpha by animateFloatAsState(
        targetValue = if (trailerActive && trailerKey != null && hoverBackdrop == null) 1f else 0f,
        animationSpec = tween(600),
        label = "hero-trailer-alpha",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .padding(horizontal = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(IptvPalette.SurfaceLift),
    ) {
        val isPoster = channel.type != ContentType.TV
        val logo = channel.logoUrl

        if (isPoster && (backdropUrl ?: logo) != null) {
            AsyncImage(
                model = backdropUrl ?: logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(kenBurnsScale),
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

        if (trailerKey != null && trailerAlpha > 0f) {
            Box(modifier = Modifier.fillMaxSize().alpha(trailerAlpha)) {
                HeroTrailerPlayer(
                    youtubeKey = trailerKey!!,
                    modifier = Modifier.fillMaxSize(),
                    muted = trailerMuted,
                    onError = { trailerActive = false },
                )
            }
        }

        androidx.compose.animation.Crossfade(
            targetState = hoverBackdrop,
            animationSpec = tween(500),
            label = "hero-hover-backdrop",
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
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
                .padding(start = 48.dp, top = 28.dp, bottom = 28.dp),
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
            Spacer(Modifier.height(16.dp))
            val showSound = trailerActive && trailerKey != null && hoverBackdrop == null
            var focusedButton by remember { mutableStateOf(-1) }
            val lastButton = when {
                showSound -> 2
                channel.type == ContentType.MOVIE -> 1
                else -> 0
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                            if (focusedButton == 0 && onPrev != null) { onPrev(); true } else false
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                            if (focusedButton == lastButton && onNext != null) { onNext(); true } else false
                        else -> false
                    }
                },
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .focusRequester(activeFocus)
                        .onFocusChanged { if (it.isFocused) focusedButton = 0 },
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
                if (channel.type == ContentType.MOVIE) {
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onMoreInfo,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusedButton = 1 },
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.65f),
                            contentColor = IptvPalette.TextPrimary,
                            focusedContainerColor = IptvPalette.SurfaceElevated,
                            focusedContentColor = IptvPalette.TextPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 10.dp).size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.hero_more_info),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                if (showSound) {
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { trailerMuted = !trailerMuted },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) focusedButton = 2 },
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.65f),
                            contentColor = IptvPalette.TextPrimary,
                            focusedContainerColor = IptvPalette.SurfaceElevated,
                            focusedContentColor = IptvPalette.TextPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = if (trailerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(
                                if (trailerMuted) R.string.hero_sound_on else R.string.hero_sound_off,
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp).size(22.dp),
                        )
                    }
                }
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
    onSeeAll: (() -> Unit)? = null,
    focusController: RailFocusController? = null,
    onContextMenu: (Channel) -> Unit = {},
    contentType: ContentType,
    progressById: Map<String, Float>,
    nowPlayingByEpgId: Map<String, String> = emptyMap(),
    onPlay: (Channel) -> Unit,
    onHover: ((Channel?) -> Unit)? = null,
) {
    // Populair-nu morphs into the Netflix-style Top 10 treatment when we have at least 3
    // matches, with the oversized numeral to the left of each poster. The cutoff keeps the
    // effect from firing on very small providers where only one or two items match.
    val isTopTen = (rail.title == ChannelsUiState.POPULAR_NOW) &&
        contentType != ContentType.TV &&
        rail.channels.size >= 3
    val railTitle = if (isTopTen) stringResource(R.string.rail_top_ten) else rail.title
    val restoreId = focusController?.restoreIdFor(rail.title)
    val rowState = remember(rail.title) {
        androidx.tv.foundation.lazy.list.TvLazyListState(focusController?.restoreIndexFor(rail.title) ?: 0, 0)
    }
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
                text = railTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = IptvPalette.TextPrimary,
                ),
            )
        }
        if (isTopTen) {
            val topTen = remember(rail.channels) { rail.channels.take(10) }
            TvLazyRow(
                state = rowState,
                // Top-10 cards are noticeably wider because of the left-side numeral, so
                // a touch more outer padding keeps the first rank from getting clipped.
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(topTen) { i, channel ->
                    TopTenCard(
                        rank = i + 1,
                        channel = channel,
                        progressFraction = progressById[channel.id],
                        onClick = { onPlay(channel) },
                        onHover = onHover,
                        focusRequester = cardRequesterFor(channel, restoreId, focusController),
                        onFocused = { focusController?.onCardFocused(rail.title, channel, i) },
                        onBlurred = { focusController?.onCardBlurred(channel.id) },
                        onLongClick = { onContextMenu(channel) },
                    )
                }
            }
        } else {
            TvLazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(rail.channels, key = { _, ch -> ch.id }) { i, channel ->
                    val progress = progressById[channel.id]
                    val requester = cardRequesterFor(channel, restoreId, focusController)
                    val onFocused: () -> Unit = { focusController?.onCardFocused(rail.title, channel, i) }
                    val onBlurred: () -> Unit = { focusController?.onCardBlurred(channel.id) }
                    val onLongClick: () -> Unit = { onContextMenu(channel) }
                    when (contentType) {
                        ContentType.TV -> LogoCard(
                            channel = channel,
                            progressFraction = progress,
                            nowPlaying = channel.epgChannelId?.let { nowPlayingByEpgId[it] },
                            onClick = { onPlay(channel) },
                            focusRequester = requester,
                            onFocused = onFocused,
                            onBlurred = onBlurred,
                            onLongClick = onLongClick,
                        )
                        ContentType.MOVIE, ContentType.SERIES ->
                            PosterCard(
                                channel = channel,
                                progressFraction = progress,
                                onClick = { onPlay(channel) },
                                onHover = onHover,
                                focusRequester = requester,
                                onFocused = onFocused,
                                onBlurred = onBlurred,
                                onLongClick = onLongClick,
                            )
                    }
                }
                if (onSeeAll != null) {
                    item(key = "__see_all__") {
                        SeeAllCard(
                            isPoster = contentType != ContentType.TV,
                            onClick = onSeeAll,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Netflix-style Top 10 card: a gigantic outlined ordinal sitting behind the left edge of
 * the poster. The numeral is a solid, bold glyph with a contrasting outline so it reads
 * equally well over dark and light artwork.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopTenCard(
    rank: Int,
    channel: Channel,
    progressFraction: Float?,
    onClick: () -> Unit,
    onHover: ((Channel?) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onBlurred: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    // Layout: a Row where the rank numeral takes the left third and the poster sits on top
    // of its right portion. Mirrors the Netflix spec: numeral is huge, ~80 % of the card
    // height, partly overlapped by the poster's left edge.
    Row(
        modifier = Modifier.width(248.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(92.dp)
                .height(252.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outlined mega-numeral — stroked so it reads as an accented rank marker
            // regardless of the poster's brightness. A 180 sp glyph in a 92 dp box is
            // just wide enough to peek out from behind the poster on the left.
            Text(
                text = rank.toString(),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 180.sp,
                    lineHeight = 180.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-8).sp,
                    drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4f,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                ),
                color = IptvPalette.TextPrimary.copy(alpha = 0.9f),
            )
        }
        // The poster overlaps the numeral's right edge by ~16 dp, the way Netflix mounts it.
        Box(modifier = Modifier.offset(x = (-16).dp)) {
            PosterCard(
                channel = channel,
                progressFraction = progressFraction,
                onClick = onClick,
                onHover = onHover,
                focusRequester = focusRequester,
                onFocused = onFocused,
                onBlurred = onBlurred,
                onLongClick = onLongClick,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LogoCard(
    channel: Channel,
    progressFraction: Float?,
    nowPlaying: String? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onBlurred: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    FocusableCard(
        width = 220.dp,
        height = 124.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        onFocusChange = { if (it) onFocused() else onBlurred() },
    ) { focused ->
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
internal fun PosterCard(
    channel: Channel,
    progressFraction: Float?,
    onClick: () -> Unit,
    onHover: ((Channel?) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onBlurred: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    FocusableCard(
        width = 168.dp,
        height = 252.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        onFocusChange = { focused ->
            onHover?.invoke(if (focused) channel else null)
            if (focused) onFocused() else onBlurred()
        },
    ) { focused ->
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
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { onFocusChange?.invoke(focused) }
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
        onLongClick = onLongClick,
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
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
private fun ChannelsSkeleton(progress: nl.vanvrouwerff.iptv.data.repo.ImportProgress? = null) {
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

        // Hero banner placeholder, carrying the import progress on a first run.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                width = null,
                height = HERO_HEIGHT,
                shape = RoundedCornerShape(24.dp),
                color = block,
            )
            importProgressLabel(progress)?.let { label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = IptvPalette.Accent,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = IptvPalette.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }

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

private const val RESTORE_FALLBACK_MS: Long = 800L

/**
 * Remembers which rail card had focus so the rails view can put the user back there after
 * a detail screen, and hands out a one-shot restore target to the matching card.
 */
@androidx.compose.runtime.Stable
class RailFocusController(
    private val type: ContentType,
    initial: RailFocusMemory?,
    private val listState: androidx.tv.foundation.lazy.list.TvLazyListState,
    private val onRemember: (ContentType, RailFocusMemory?) -> Unit,
) {
    private var pending by mutableStateOf(initial)

    fun hasPendingRestore(): Boolean = pending != null
    fun restoreIdFor(railKey: String): String? = pending?.takeIf { it.railKey == railKey }?.itemId
    fun restoreIndexFor(railKey: String): Int = pending?.takeIf { it.railKey == railKey }?.itemIndex ?: 0
    fun restored() { pending = null }
    fun abandonRestore() { pending = null }

    var focusedCard: Channel? = null
        private set
    private val requesters = HashMap<String, FocusRequester>()

    fun register(itemId: String, requester: FocusRequester) { requesters[itemId] = requester }
    fun unregister(itemId: String, requester: FocusRequester) {
        if (requesters[itemId] === requester) requesters.remove(itemId)
    }
    fun refocus(itemId: String) { runCatching { requesters[itemId]?.requestFocus() } }

    fun onCardBlurred(itemId: String) {
        if (focusedCard?.id == itemId) focusedCard = null
    }

    fun onCardFocused(railKey: String, channel: Channel, itemIndex: Int) {
        focusedCard = channel
        onRemember(
            type,
            RailFocusMemory(
                railKey = railKey,
                itemId = channel.id,
                itemIndex = itemIndex,
                listIndex = listState.firstVisibleItemIndex,
                listOffset = listState.firstVisibleItemScrollOffset,
            ),
        )
    }

    fun onHeroFocused() {
        focusedCard = null
        if (pending == null) onRemember(type, null)
    }
}

@Composable
private fun cardRequesterFor(
    channel: Channel,
    restoreId: String?,
    controller: RailFocusController?,
): FocusRequester? {
    if (controller == null) return null
    val requester = remember { FocusRequester() }
    DisposableEffect(channel.id, requester) {
        controller.register(channel.id, requester)
        onDispose { controller.unregister(channel.id, requester) }
    }
    if (restoreId != null && channel.id == restoreId) {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) controller.restored()
        }
    }
    return requester
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CardContextMenu(
    channel: Channel,
    isFavorite: Boolean,
    hasProgress: Boolean,
    onToggleFavorite: () -> Unit,
    onRemoveFromContinue: () -> Unit,
    onMarkWatched: () -> Unit,
    onManageChannels: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.6f)),
    )
    Column(
        modifier = modifier
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .width(420.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(IptvPalette.SurfaceLift)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = IptvPalette.TextPrimary,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        val isTv = channel.type == ContentType.TV
        val favLabel = when {
            isTv && isFavorite -> R.string.context_favorite_remove
            isTv -> R.string.context_favorite_add
            isFavorite -> R.string.detail_remove_from_list
            else -> R.string.detail_add_to_list
        }
        ContextMenuItem(
            label = stringResource(favLabel),
            onClick = onToggleFavorite,
            modifier = Modifier.focusRequester(firstFocus),
        )
        if (!isTv && hasProgress) {
            ContextMenuItem(stringResource(R.string.context_remove_continue), onRemoveFromContinue)
        }
        if (!isTv) {
            ContextMenuItem(stringResource(R.string.context_mark_watched), onMarkWatched)
        }
        if (isTv) {
            ContextMenuItem(stringResource(R.string.favorites_manage), onManageChannels)
        }
        ContextMenuItem(stringResource(R.string.context_close), onDismiss)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContextMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllCard(isPoster: Boolean, onClick: () -> Unit) {
    FocusableCard(
        width = if (isPoster) 168.dp else 220.dp,
        height = if (isPoster) 252.dp else 124.dp,
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier.fillMaxSize().background(IptvPalette.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (focused) Color.White else IptvPalette.TextSecondary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rail_see_all),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) Color.White else IptvPalette.TextSecondary,
                    ),
                )
            }
        }
    }
}

private suspend fun lookupHeroBundle(channel: Channel) = runCatching {
    IptvApp.get().tmdbMovieDetails.lookupMovie(
        channelId = channel.id,
        title = TmdbCatalogueMatcher.normalize(channel.name).ifBlank { channel.name },
        releaseYear = null,
    )
}.getOrNull()

/**
 * Live-TV hero: a compact "last watched" card with Now/Next, so the favourites rail sits
 * directly underneath in view instead of below a 340dp banner of a channel logo.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CompactTvHero(
    channel: Channel,
    isLastWatched: Boolean,
    nowPlaying: String?,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
) {
    var nextTitle by remember(channel.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(channel.epgChannelId) {
        val key = channel.epgChannelId ?: return@LaunchedEffect
        nextTitle = runCatching {
            IptvApp.get().database.channelDao()
                .getNextProgrammeFor(key, System.currentTimeMillis())?.title
        }.getOrNull()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .height(COMPACT_TV_HERO_HEIGHT)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(IptvPalette.AccentDeep.copy(alpha = 0.55f), IptvPalette.SurfaceLift),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 180.dp, height = 104.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IptvPalette.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = IptvPalette.TextSecondary,
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (isLastWatched) {
                AccentPill(text = stringResource(R.string.channels_last_watched))
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = IptvPalette.TextPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!nowPlaying.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.epg_now_prefix, nowPlaying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.AccentSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            nextTitle?.let {
                Text(
                    text = stringResource(R.string.player_banner_next, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Button(onClick = onPlay, modifier = Modifier.focusRequester(focusRequester)) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.padding(start = 10.dp).size(22.dp),
            )
            Text(
                text = stringResource(R.string.channels_play),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

private val COMPACT_TV_HERO_HEIGHT = 140.dp

@Composable
private fun importProgressLabel(progress: nl.vanvrouwerff.iptv.data.repo.ImportProgress?): String? {
    if (progress == null) return null
    val count = java.text.NumberFormat.getIntegerInstance(java.util.Locale("nl", "NL")).format(progress.count)
    return when (progress.stage) {
        nl.vanvrouwerff.iptv.data.repo.ImportProgress.Stage.Downloading -> stringResource(R.string.import_downloading)
        nl.vanvrouwerff.iptv.data.repo.ImportProgress.Stage.Live -> stringResource(R.string.import_live, count)
        nl.vanvrouwerff.iptv.data.repo.ImportProgress.Stage.Movies -> stringResource(R.string.import_movies, count)
        nl.vanvrouwerff.iptv.data.repo.ImportProgress.Stage.Series -> stringResource(R.string.import_series, count)
        nl.vanvrouwerff.iptv.data.repo.ImportProgress.Stage.Saving -> stringResource(R.string.import_saving, count)
    }
}
