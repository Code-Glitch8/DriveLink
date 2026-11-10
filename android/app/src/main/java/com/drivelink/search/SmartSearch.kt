package com.drivelink.search

import com.drivelink.navigation.NominatimGeocoder
import com.drivelink.navigation.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object TooShort : SearchState
    data object Loading : SearchState
    data class Results(val hits: List<SearchHit>, val fromFallback: Boolean) : SearchState
    data object Empty : SearchState
    data class Error(val message: String) : SearchState
}

/**
 * Debounced, cached destination search. Provider order: Photon (autocomplete) -> Nominatim (OSM, 1 req/s, no
 * autocomplete but fine for a single query) -> nothing. A previous request is cancelled when the user keeps typing.
 */
class SmartSearch(
    private val scope: CoroutineScope,
    private val photon: PhotonGeocoder = PhotonGeocoder(),
    private val nominatim: NominatimGeocoder = NominatimGeocoder(),
    private val debounceMs: Long = 350L,
    private val minChars: Int = 3,
) {
    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private val cache = SearchCache<SearchState.Results>()
    private var job: Job? = null

    fun clear() { job?.cancel(); _state.value = SearchState.Idle }

    /** Call on every keystroke. [immediate] skips the debounce (the user pressed the search key). */
    fun onQuery(text: String, biasLat: Double?, biasLon: Double?, immediate: Boolean = false) {
        job?.cancel()
        val q = text.trim()
        if (q.isEmpty()) { _state.value = SearchState.Idle; return }
        if (q.length < minChars) { _state.value = SearchState.TooShort; return }
        val key = SearchCache.key(q, biasLat, biasLon)
        cache.get(key)?.let { _state.value = it; return }
        job = scope.launch {
            _state.value = SearchState.Loading
            if (!immediate) delay(debounceMs)
            try {
                val (places, fallback) = try {
                    photon.search(q, biasLat, biasLon) to false
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { nominatim.search(q) to true }
                val hits = SearchSupport.rank(q, places, biasLat, biasLon)
                if (hits.isEmpty()) _state.value = SearchState.Empty
                else SearchState.Results(hits, fallback).also { cache.put(key, it); _state.value = it }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.value = SearchState.Error(e.message ?: "Search failed. Check your connection.") }
        }
    }
}

private typealias CancellationException = kotlinx.coroutines.CancellationException
