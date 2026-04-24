package nl.vanvrouwerff.iptv.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.WatchedEpisodeEntity
import nl.vanvrouwerff.iptv.data.db.toDomain
import nl.vanvrouwerff.iptv.data.tmdb.MovieMatcher
import nl.vanvrouwerff.iptv.data.tmdb.SeriesMatcher
import nl.vanvrouwerff.iptv.data.tmdb.TmdbPopularRepository

data class Rail(
    val title: String,
    val channels: List<Channel>,
    /**
     * When true, clicks on items in this rail skip the detail screen and play directly
     * (with auto-resume if saved progress exists). Used for the "Verder kijken" rail.
     */
    val isDirectPlay: Boolean = false,
)

data class ChannelsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val selectedType: ContentType = ContentType.TV,
    /** Channels for the currently selected tab only. Keeps peak memory bounded. */
    val channels: List<Channel> = emptyList(),
    /** Categories for the currently selected tab only. */
    val categories: List<String> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Channel> = emptyList(),
    val hasAnyChannels: Boolean = false,
    /**
     * Total channels in the Room cache (all tabs combined). Used by the screen to keep
     * the skeleton up until the initial bulk insert has pushed past a meaningful
     * threshold — `hasAnyChannels` flips true on the very first inserted row, which
     * otherwise lets the skeleton vanish and reappear as rows flood in.
     */
    val totalChannelCount: Int = 0,
    val lastRefreshAtMs: Long = 0L,
    val lastWatchedId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
    /** Map of EPG channel key (tvg-id) → currently-airing programme title. */
    val nowPlayingByEpgId: Map<String, String> = emptyMap(),
    /**
     * Movies AND series episodes the user has started but not finished, sorted by most
     * recently updated. Episodes are represented as Channel objects with id prefixed
     * `xt-episode:` so MainActivity's click router sends them straight to the player.
     * Movies carry ContentType.MOVIE, episodes ContentType.SERIES — the rail filters by
     * the current tab so each tab only shows its own "Verder kijken" items.
     */
    val continueWatching: List<Channel> = emptyList(),
    /**
     * Id → progress fraction (0..1) for items with saved watch progress. Used by the card
     * rendering path to draw a thin progress bar across the bottom of any card whose id
     * appears here — regardless of whether it's on the "Verder kijken" rail.
     */
    val progressById: Map<String, Float> = emptyMap(),
    /**
     * TMDB-popular series intersected with the user's catalogue, in TMDB popularity order.
     * Only populated when the Series tab is active. Empty when TMDB isn't configured or
     * when no popular titles exist on the user's provider.
     */
    val popularSeries: List<Channel> = emptyList(),
    /**
     * TMDB-popular movies intersected with the user's catalogue, in TMDB popularity order.
     * Mirrors [popularSeries]: only populated when the Movies tab is active.
     */
    val popularMovies: List<Channel> = emptyList(),
    val managingFavorites: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val error: String? = null,
    /**
     * Channels with `streamUrl != null`, derived once per channels-update so click handlers
     * don't re-filter the catalogue (~40k items) on every recompose.
     */
    val playableChannels: List<Channel> = emptyList(),
    /** TV channels the user has starred, in catalogue order. Only meaningful on the TV tab. */
    val favoriteChannels: List<Channel> = emptyList(),
    val hero: Channel? = null,
    /**
     * Featured rotation for the hero banner. Netflix-style: the first slot is the user's
     * last-watched item (when present), the rest are drawn from the Populair-nu rail and
     * the first items of the main category rails. Capped so the carousel stays short
     * enough that every slot gets meaningful screen time.
     */
    val heroes: List<Channel> = emptyList(),
    val rails: List<Rail> = emptyList(),
    /** Display name of the currently active profile, or null while we're still resolving it. */
    val activeProfileName: String? = null,
) {
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    companion object {
        const val UNCATEGORIZED = "Overig"
        const val MY_LIST = "Mijn lijst"
        const val CONTINUE_WATCHING = "Verder kijken"
        const val POPULAR_NOW = "Populair nu"
        const val MAX_PER_RAIL = 200
        const val MAX_RAILS = 60
    }
}

/**
 * Pure derivations from raw state — no I/O, no allocation amplification. Hoisted out of
 * the data class so the ViewModel can run them off-main once per input change instead of
 * Compose re-evaluating them on every recomposition.
 */
private object ChannelsDerivations {
    fun playable(channels: List<Channel>): List<Channel> =
        channels.filter { it.streamUrl != null }

    fun favorites(
        selectedType: ContentType,
        channels: List<Channel>,
        favoriteIds: Set<String>,
    ): List<Channel> =
        if (selectedType != ContentType.TV || favoriteIds.isEmpty()) emptyList()
        else channels.filter { it.id in favoriteIds }

    fun hero(
        selectedType: ContentType,
        channels: List<Channel>,
        lastWatchedId: String?,
    ): Channel? {
        if (lastWatchedId != null) {
            channels.firstOrNull { it.id == lastWatchedId }?.let { return it }
        }
        // Series rows carry a null streamUrl — clicking one opens the detail screen, not
        // the player. Requiring streamUrl here would blank the Series tab entirely.
        return if (selectedType == ContentType.SERIES) channels.firstOrNull()
        else channels.firstOrNull { it.streamUrl != null }
    }

    /**
     * Up to [HERO_CAROUSEL_MAX] featured titles for the rotating hero banner. Dedup by id,
     * prefer posters with artwork (logoUrl) so the carousel has visual weight on each slot.
     * Order: last-watched first, then popular rail, then the first representative card from
     * each category. Live-TV tab keeps the single-item behaviour (no carousel) because live
     * channel logos don't carry the kind of hero-quality artwork posters do.
     */
    fun heroes(
        selectedType: ContentType,
        channels: List<Channel>,
        lastWatchedId: String?,
        popularMovies: List<Channel>,
        popularSeries: List<Channel>,
        continueWatching: List<Channel>,
    ): List<Channel> {
        if (channels.isEmpty()) return emptyList()
        if (selectedType == ContentType.TV) {
            return listOfNotNull(hero(selectedType, channels, lastWatchedId))
        }
        val seen = HashSet<String>()
        val out = mutableListOf<Channel>()
        fun tryAdd(ch: Channel?) {
            if (ch == null) return
            if (out.size >= HERO_CAROUSEL_MAX) return
            if (!seen.add(ch.id)) return
            // Hero banner relies on a backdrop image — drop anything without one so the
            // carousel never rotates into a flat gradient slot mid-cycle.
            if (ch.logoUrl.isNullOrBlank()) return
            out.add(ch)
        }
        // 1. Last-watched (if it's in this tab's channel list).
        if (lastWatchedId != null) {
            tryAdd(channels.firstOrNull { it.id == lastWatchedId })
        }
        // 2. Most recently started item in this tab.
        continueWatching.firstOrNull { it.type == selectedType }?.let(::tryAdd)
        // 3. Top of the populair-nu rail for this tab.
        val popular = when (selectedType) {
            ContentType.MOVIE -> popularMovies
            ContentType.SERIES -> popularSeries
            else -> emptyList()
        }
        popular.take(3).forEach(::tryAdd)
        // 4. Representative first item from a diverse set of categories. `distinctBy`
        //    groupTitle keeps the carousel visually varied.
        channels.asSequence()
            .filter { !it.logoUrl.isNullOrBlank() }
            .distinctBy { it.groupTitle }
            .take(HERO_CAROUSEL_MAX)
            .forEach(::tryAdd)
        if (out.isEmpty()) hero(selectedType, channels, lastWatchedId)?.let(::tryAdd)
        return out
    }

    const val HERO_CAROUSEL_MAX: Int = 5

    fun rails(
        selectedType: ContentType,
        channels: List<Channel>,
        categories: List<String>,
        favoriteIds: Set<String>,
        continueWatching: List<Channel>,
        popularSeries: List<Channel>,
        popularMovies: List<Channel>,
    ): List<Rail> {
        if (channels.isEmpty()) return emptyList()
        return buildList {
            if (selectedType != ContentType.TV) {
                val cw = continueWatching.filter { it.type == selectedType }
                if (cw.isNotEmpty()) {
                    add(
                        Rail(
                            title = ChannelsUiState.CONTINUE_WATCHING,
                            channels = cw.take(ChannelsUiState.MAX_PER_RAIL),
                            isDirectPlay = true,
                        ),
                    )
                }
            }
            if (selectedType == ContentType.SERIES && popularSeries.isNotEmpty()) {
                add(Rail(ChannelsUiState.POPULAR_NOW, popularSeries.take(ChannelsUiState.MAX_PER_RAIL)))
            }
            if (selectedType == ContentType.MOVIE && popularMovies.isNotEmpty()) {
                add(Rail(ChannelsUiState.POPULAR_NOW, popularMovies.take(ChannelsUiState.MAX_PER_RAIL)))
            }
            // Favorites as the first rail on every tab — on TV this is the "Mijn zenders"
            // shortcut to the user's starred channels, without hiding the rest of the
            // catalogue behind a manage-mode wall.
            if (favoriteIds.isNotEmpty()) {
                val favs = channels.filter { it.id in favoriteIds }
                if (favs.isNotEmpty()) {
                    add(Rail(ChannelsUiState.MY_LIST, favs.take(ChannelsUiState.MAX_PER_RAIL)))
                }
            }
            val grouped = channels.groupBy { it.groupTitle ?: ChannelsUiState.UNCATEGORIZED }
            val categoryRails = buildList {
                categories.forEach { cat -> grouped[cat]?.let { add(cat to it) } }
                grouped[ChannelsUiState.UNCATEGORIZED]?.let { add(ChannelsUiState.UNCATEGORIZED to it) }
                grouped.forEach { (cat, chs) ->
                    if (cat != ChannelsUiState.UNCATEGORIZED && cat !in categories) add(cat to chs)
                }
            }.map { (title, chs) -> Rail(title, chs.take(ChannelsUiState.MAX_PER_RAIL)) }
            addAll(categoryRails.take(ChannelsUiState.MAX_RAILS - size))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChannelsViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val dao = app.database.channelDao()
    private val profileDao = app.database.profileDao()

    private val _state = MutableStateFlow(ChannelsUiState())
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    // Dedicated flows so Room queries only re-subscribe when selection / query actually change.
    private val selectedTypeFlow = MutableStateFlow(ContentType.TV)
    private val searchQueryFlow = MutableStateFlow("")
    private val activeProfileIdFlow = app.activeProfileId

    // Keep one hot StateFlow per content type so tab switches are instant after the first
    // load. `flatMapLatest` on selectedType would otherwise cancel the previous query and
    // force Room to re-read 20–40k rows each time the user flips tabs. Eager `stateIn` keeps
    // all three warm; memory cost is bounded by `limitFor` plus the country filter.
    private val channelsByType: Map<ContentType, StateFlow<List<Channel>>> =
        ContentType.values().associateWith { type ->
            dao.observeChannelsByType(type.name, limitFor(type))
                .map { rows -> rows.map { it.toDomain() }.distinctBy { it.id } }
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        }

    private val categoriesByType: Map<ContentType, StateFlow<List<String>>> =
        ContentType.values().associateWith { type ->
            dao.observeCategoriesByType(type.name)
                .map { rows -> rows.map { it.name }.distinct() }
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        }

    init {
        // Emit the cached slice for the selected tab. Because each `channelsByType` entry is
        // a warm StateFlow, switching tabs re-emits the already-computed list synchronously —
        // the expensive Room query + entity mapping only runs once per type, in the background.
        selectedTypeFlow
            .flatMapLatest { type -> channelsByType.getValue(type) }
            .onEach { channels ->
                _state.update { it.copy(loading = false, channels = channels) }
            }
            .launchIn(viewModelScope)

        selectedTypeFlow
            .flatMapLatest { type -> categoriesByType.getValue(type) }
            .onEach { cats -> _state.update { it.copy(categories = cats) } }
            .launchIn(viewModelScope)

        // Search results for the current tab, debounced so we don't hit Room on every keystroke.
        combine(
            searchQueryFlow.debounce(200),
            selectedTypeFlow,
        ) { q, type -> q to type }
            .distinctUntilChanged()
            .flatMapLatest { (q, type) ->
                if (q.isBlank()) flowOf(emptyList())
                else dao.searchChannels(type.name, q.trim(), limit = SEARCH_LIMIT)
                    .map { rows -> rows.map { it.toDomain() } }
            }
            .flowOn(Dispatchers.Default)
            .onEach { results -> _state.update { it.copy(searchResults = results) } }
            .launchIn(viewModelScope)

        // Favourites / last-watched / recent searches are per-profile: re-subscribe whenever
        // the active profile id changes so switching profiles swaps these lists atomically.
        activeProfileIdFlow
            .flatMapLatest { dao.observeFavoriteIds(it) }
            .map { it.toHashSet() }
            .onEach { ids -> _state.update { it.copy(favoriteIds = ids) } }
            .launchIn(viewModelScope)

        activeProfileIdFlow
            .flatMapLatest { app.settings.lastWatchedChannelId(it) }
            .onEach { id -> _state.update { it.copy(lastWatchedId = id) } }
            .launchIn(viewModelScope)

        activeProfileIdFlow
            .flatMapLatest { app.settings.recentSearches(it) }
            .onEach { list -> _state.update { it.copy(recentSearches = list) } }
            .launchIn(viewModelScope)

        // Active-profile name for the TopBar chip. Looks up the name by id so the chip
        // always reflects the current row in the profiles table, even after a rename.
        combine(activeProfileIdFlow, profileDao.observeProfiles()) { id, profiles ->
            profiles.firstOrNull { it.id == id }?.name
        }
            .onEach { name -> _state.update { it.copy(activeProfileName = name) } }
            .launchIn(viewModelScope)

        app.settings.lastRefreshSuccessAt
            .onEach { ts -> _state.update { it.copy(lastRefreshAtMs = ts) } }
            .launchIn(viewModelScope)

        // EPG "now playing" per channel. We tick the clock every minute so the list stays
        // current without needing to refetch XMLTV — the table is static, only the
        // `now` cutoff moves.
        val nowTicker = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(EPG_TICK_MS)
            }
        }
        nowTicker
            .flatMapLatest { now -> dao.observeNowPlaying(now) }
            .map { list -> list.associate { it.channelKey to it.title } }
            .flowOn(Dispatchers.Default)
            .onEach { map -> _state.update { it.copy(nowPlayingByEpgId = map) } }
            .launchIn(viewModelScope)

        dao.observeChannelCount()
            .onEach { count ->
                _state.update {
                    it.copy(hasAnyChannels = count > 0, totalChannelCount = count)
                }
            }
            .launchIn(viewModelScope)

        // Continue-watching list, merged across movies + series episodes. The Movies and
        // Series tabs each filter this list to their own type before rendering, so both
        // tabs get a "Verder kijken" rail populated from the same source of truth.
        activeProfileIdFlow.flatMapLatest { profileId ->
            combine(
                dao.observeContinueWatching(
                    profileId = profileId,
                    type = ContentType.MOVIE.name,
                    finishThresholdMs = CONTINUE_FINISH_THRESHOLD_MS,
                    limit = CONTINUE_LIMIT,
                ),
                dao.observeContinueWatchingEpisodes(
                    profileId = profileId,
                    finishThresholdMs = CONTINUE_FINISH_THRESHOLD_MS,
                    limit = CONTINUE_LIMIT,
                ),
            ) { movieRows, episodeRows -> movieRows to episodeRows }
        }.map { (movieRows, episodeRows) ->
            val progress = mutableMapOf<String, Float>()
            val items = buildList<Pair<Channel, Long>> {
                movieRows.forEach { row ->
                    val ch = row.channel.toDomain()
                    progress[ch.id] = fraction(row.positionMs, row.durationMs)
                    add(ch to row.progressUpdatedAt)
                }
                episodeRows.forEach { row ->
                    val ch = row.episode.toContinueWatchingChannel()
                    progress[ch.id] = fraction(row.positionMs, row.durationMs)
                    add(ch to row.progressUpdatedAt)
                }
            }
            items.sortedByDescending { it.second }
                .map { it.first }
                .take(CONTINUE_LIMIT) to progress.toMap()
        }
            .flowOn(Dispatchers.Default)
            .onEach { (list, progress) ->
                _state.update { it.copy(continueWatching = list, progressById = progress) }
            }
            .launchIn(viewModelScope)

        // "Populair nu" rails (Movies + Series). Two layers of caching:
        //  1. The matched-IDs cache (24h) lets the rail render at cold start as soon as the
        //     catalogue Flow emits — no waiting on TMDB or 32k-row title normalisation.
        //  2. If the cache is stale/missing, we fetch TMDB and re-run matching in the
        //     background, then overwrite the cache and re-emit. Fresh users still get the
        //     rail on first launch, just after the usual one-time match.
        setupPopularRail(
            type = ContentType.MOVIE,
            cacheKey = TmdbPopularRepository.KEY_MATCHED_POPULAR_MOVIES,
            fetchTmdb = { app.tmdbPopular.getPopularMovies() },
            match = { tmdb, chans -> MovieMatcher.match(tmdb, chans) },
            emit = { list -> _state.update { it.copy(popularMovies = list) } },
        )
        setupPopularRail(
            type = ContentType.SERIES,
            cacheKey = TmdbPopularRepository.KEY_MATCHED_POPULAR_SERIES,
            fetchTmdb = { app.tmdbPopular.getPopularTv() },
            match = { tmdb, chans -> SeriesMatcher.match(tmdb, chans) },
            emit = { list -> _state.update { it.copy(popularSeries = list) } },
        )

        // Derived view fields (rails, hero, favoriteChannels, playableChannels). Computed
        // off-main once per input change, NOT per recompose. Crucially, the EPG ticker
        // (which writes nowPlayingByEpgId) is not in the input set, so rails don't get
        // rebuilt every minute just because "Now on" labels updated.
        _state
            .map { s ->
                DerivationInputs(
                    selectedType = s.selectedType,
                    channels = s.channels,
                    categories = s.categories,
                    favoriteIds = s.favoriteIds,
                    continueWatching = s.continueWatching,
                    popularSeries = s.popularSeries,
                    popularMovies = s.popularMovies,
                    lastWatchedId = s.lastWatchedId,
                )
            }
            .distinctUntilChanged()
            .map { inputs ->
                DerivedFields(
                    playable = ChannelsDerivations.playable(inputs.channels),
                    favorites = ChannelsDerivations.favorites(
                        inputs.selectedType, inputs.channels, inputs.favoriteIds,
                    ),
                    hero = ChannelsDerivations.hero(
                        inputs.selectedType, inputs.channels, inputs.lastWatchedId,
                    ),
                    heroes = ChannelsDerivations.heroes(
                        inputs.selectedType,
                        inputs.channels,
                        inputs.lastWatchedId,
                        inputs.popularMovies,
                        inputs.popularSeries,
                        inputs.continueWatching,
                    ),
                    rails = ChannelsDerivations.rails(
                        inputs.selectedType,
                        inputs.channels,
                        inputs.categories,
                        inputs.favoriteIds,
                        inputs.continueWatching,
                        inputs.popularSeries,
                        inputs.popularMovies,
                    ),
                )
            }
            .flowOn(Dispatchers.Default)
            .onEach { d ->
                _state.update {
                    it.copy(
                        playableChannels = d.playable,
                        favoriteChannels = d.favorites,
                        hero = d.hero,
                        heroes = d.heroes,
                        rails = d.rails,
                    )
                }
            }
            .launchIn(viewModelScope)

        // Only refresh when the cached catalogue is stale. Running the 30-60s Xtream pull
        // on every cold start makes the Formuler feel sluggish. When we DO refresh, it
        // runs in the background — the user browses cached data immediately while the
        // source-status pill shows "Vernieuwen…".
        viewModelScope.launch {
            val last = app.settings.lastRefreshSuccessAt.first()
            val ageMs = System.currentTimeMillis() - last
            if (last == 0L || ageMs > FRESH_THRESHOLD_MS) {
                refresh()
            }
        }
    }

    fun selectType(type: ContentType) {
        if (selectedTypeFlow.value == type) return
        selectedTypeFlow.value = type
        searchQueryFlow.value = ""
        _state.update {
            it.copy(
                selectedType = type,
                // Leaving manage mode when switching away from TV is less confusing.
                managingFavorites = if (type == ContentType.TV) it.managingFavorites else false,
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
    }

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
        _state.update { it.copy(searchQuery = query) }
    }

    /** Promote the current query to "recents". Called when the user picks a result. */
    fun rememberCurrentSearch() {
        val q = _state.value.searchQuery.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            app.settings.pushRecentSearch(activeProfileIdFlow.value, q)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            app.settings.clearRecentSearches(activeProfileIdFlow.value)
        }
    }

    fun setManagingFavorites(enabled: Boolean) {
        if (_state.value.managingFavorites == enabled) return
        _state.update { it.copy(managingFavorites = enabled) }
    }

    fun toggleFavorite(channelId: String) {
        val isFav = channelId in _state.value.favoriteIds
        val profileId = activeProfileIdFlow.value
        viewModelScope.launch {
            if (isFav) dao.removeFavorite(profileId, channelId)
            else dao.addFavorite(profileId, channelId)
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            // The use case fires onCatalogueReady once channels + categories land, before it
            // starts the EPG write. We flip `refreshing = false` then so the user sees their
            // rails while the EPG persists in the background (~2 extra minutes on the Formuler).
            val result = app.refreshUseCase(
                onCatalogueReady = {
                    _state.update { it.copy(refreshing = false) }
                },
            )
            _state.update {
                it.copy(
                    refreshing = false,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /**
     * Sets up a cache-first "Populair nu" rail. The matched channel IDs are the source of
     * truth: seeded from the 24h cache on launch (so the rail appears the instant Room
     * emits channels), then refreshed in the background when stale. Each refresh overwrites
     * the IDs Flow, which fans out through the hydration combine below and re-emits the
     * latest order/contents.
     */
    private fun <T> setupPopularRail(
        type: ContentType,
        cacheKey: String,
        fetchTmdb: suspend () -> List<T>,
        match: (List<T>, List<Channel>) -> List<Channel>,
        emit: (List<Channel>) -> Unit,
    ) {
        val idsFlow = MutableStateFlow<List<String>>(emptyList())

        // Hydrate cached/fresh IDs against the currently-loaded catalogue. Missing IDs are
        // silently dropped (e.g. after a source change renamed or removed the entry).
        combine(idsFlow, channelsByType.getValue(type)) { ids, channels ->
            if (ids.isEmpty() || channels.isEmpty()) emptyList()
            else {
                val byId = channels.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .onEach(emit)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val cached = runCatching { app.tmdbPopular.readMatchedIds(cacheKey) }.getOrNull()
            if (cached != null && cached.ids.isNotEmpty()) {
                // Paint the rail from yesterday's result immediately — even if stale, a
                // stale rail beats a blank one while we decide whether to refresh.
                idsFlow.value = cached.ids
            }
            if (cached != null && app.tmdbPopular.isFresh(cached)) return@launch

            // Stale or missing: wait for the catalogue to populate, then do the real work.
            val channels = channelsByType.getValue(type).first { it.isNotEmpty() }
            val tmdb = runCatching { fetchTmdb() }.getOrElse { return@launch }
            if (tmdb.isEmpty()) return@launch
            val matched = withContext(Dispatchers.Default) { match(tmdb, channels) }
            val newIds = matched.map { it.id }
            runCatching { app.tmdbPopular.writeMatchedIds(cacheKey, newIds) }
            idsFlow.value = newIds
        }
    }

    private fun limitFor(type: ContentType): Int = when (type) {
        // TV needs to cover favorites management, so give it headroom over the usual ~26k.
        ContentType.TV -> 40000
        // Movies/Series only feed the rails (200 items × 60 rails = 12k); cap well above that.
        ContentType.MOVIE, ContentType.SERIES -> 20000
    }

    private companion object {
        const val SEARCH_LIMIT = 200
        const val CONTINUE_LIMIT = 20
        const val CONTINUE_FINISH_THRESHOLD_MS = 30_000L
        // One EPG tick per minute keeps the "Now on" labels live without hammering Room.
        const val EPG_TICK_MS = 60_000L
        // Catalogue cache older than this triggers an automatic refresh; newer is reused.
        const val FRESH_THRESHOLD_MS: Long = 6L * 3_600_000L
    }
}

private fun WatchedEpisodeEntity.toContinueWatchingChannel(): Channel = Channel(
    id = episodeId,
    name = "$seriesName \u2014 S${seasonNumber}E${episodeNumber}: $episodeTitle",
    logoUrl = coverUrl,
    groupTitle = seriesName,
    streamUrl = streamUrl,
    epgChannelId = null,
    type = ContentType.SERIES,
)

private fun fraction(position: Long, duration: Long): Float =
    if (duration <= 0L) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

private data class DerivationInputs(
    val selectedType: ContentType,
    val channels: List<Channel>,
    val categories: List<String>,
    val favoriteIds: Set<String>,
    val continueWatching: List<Channel>,
    val popularSeries: List<Channel>,
    val popularMovies: List<Channel>,
    val lastWatchedId: String?,
)

private data class DerivedFields(
    val playable: List<Channel>,
    val favorites: List<Channel>,
    val hero: Channel?,
    val heroes: List<Channel>,
    val rails: List<Rail>,
)
