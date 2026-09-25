package nl.vanvrouwerff.iptv.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.toDomain
import nl.vanvrouwerff.iptv.ui.channels.ChannelsUiState

data class CategoryBrowseState(
    val categories: List<String> = emptyList(),
    val selected: String? = null,
    val items: List<Channel> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel : ViewModel() {

    private val dao = IptvApp.get().database.channelDao()
    private val type = MutableStateFlow<ContentType?>(null)
    private val selected = MutableStateFlow<String?>(null)

    private val kids = IptvApp.get().kidsMode

    private val categories = combine(type, kids) { t, k -> t to k }.flatMapLatest { (t, k) ->
        if (t == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else dao.observeGroupTitlesByType(t.name).map { titles ->
            titles.map { it ?: ChannelsUiState.UNCATEGORIZED }.distinct()
                .filterNot { k && nl.vanvrouwerff.iptv.data.AdultContent.isAdultCategory(it) }
        }
    }

    private val items = combine(type, selected) { t, c -> t to c }.flatMapLatest { (t, c) ->
        when {
            t == null || c == null -> kotlinx.coroutines.flow.flowOf(null)
            c == ChannelsUiState.UNCATEGORIZED ->
                combine(
                    dao.observeByTypeUncategorized(t.name),
                    dao.observeByTypeAndGroup(t.name, c),
                ) { a, b -> (a + b).map { it.toDomain() } }
            else -> dao.observeByTypeAndGroup(t.name, c).map { list -> list.map { it.toDomain() } }
        }
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<CategoryBrowseState> =
        combine(categories, selected, items) { cats, sel, list ->
            val effective = sel?.takeIf { it in cats } ?: cats.firstOrNull()
            if (effective != sel && effective != null) selected.value = effective
            CategoryBrowseState(
                categories = cats,
                selected = effective,
                items = list.orEmpty(),
                loading = list == null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryBrowseState())

    fun load(contentType: ContentType, initialCategory: String?) {
        if (type.value != contentType) {
            type.value = contentType
            selected.value = initialCategory
        } else if (initialCategory != null) {
            selected.value = initialCategory
        }
    }

    fun select(category: String) {
        selected.value = category
    }
}
