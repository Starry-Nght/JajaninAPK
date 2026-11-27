package com.jajanin.app.feature.buyer.store_discovery

import com.jajanin.app.MainActivity
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.data.repository.SupabaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel-equivalent coordinator for Buyer Store Discovery & Debounced Paginated Search.
 * Keeps all network & pagination state out of Canvas UI files.
 */
internal object StoreSearchViewModel {
    private const val DEBOUNCE_MS = 320L

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var directoryJob: Job? = null

    fun openStoreDiscovery(activity: MainActivity, initialCategory: String? = null) {
        if (!SupabaseRepository.hasSession()) {
            android.widget.Toast.makeText(activity, "Masuk dulu untuk menjelajahi warung.", android.widget.Toast.LENGTH_SHORT).show()
            activity.showAuthForm(false)
            return
        }
        if (initialCategory != null) {
            activity.ui.storeSearchCategory = initialCategory
        }
        if (activity.ui.currentScreen != Screen.STORE_SEARCH) {
            activity.ui.go(Screen.STORE_SEARCH)
        }
        if (activity.ui.storeSearchResults.isEmpty() || initialCategory != null) {
            triggerSearch(activity, immediate = true)
        }
    }

    fun onQueryChanged(activity: MainActivity, newQuery: String, immediate: Boolean = false) {
        val trimmed = newQuery.trimStart()
        val changed = activity.ui.storeSearchQuery != trimmed
        activity.ui.storeSearchQuery = trimmed
        activity.ui.invalidate()
        if (changed || immediate) {
            triggerSearch(activity, immediate = immediate)
        }
    }

    fun onCategorySelected(activity: MainActivity, categoryKey: String) {
        if (activity.ui.storeSearchCategory == categoryKey && activity.ui.storeSearchResults.isNotEmpty()) return
        activity.ui.storeSearchCategory = categoryKey
        activity.ui.scroll = 0f
        activity.ui.invalidate()
        triggerSearch(activity, immediate = true)
    }

    fun triggerSearch(activity: MainActivity, immediate: Boolean = false) {
        if (!SupabaseRepository.hasSession()) return
        searchJob?.cancel()
        loadMoreJob?.cancel()
        activity.ui.storeSearchLoading = true
        activity.ui.storeSearchLoadingMore = false
        activity.ui.storeSearchError = null
        activity.ui.invalidate()

        val q = activity.ui.storeSearchQuery
        val cat = activity.ui.storeSearchCategory
        searchJob = activity.authScope.launch {
            try {
                if (!immediate) delay(DEBOUNCE_MS)
                val response = StoreDiscoveryRepository.searchPublicStores(
                    query = q,
                    category = cat,
                    limit = StoreDiscoveryRepository.PAGE_SIZE,
                    offset = 0,
                )
                activity.ui.storeSearchResults = response.stores
                activity.ui.storeSearchTotal = response.total
                activity.ui.storeSearchHasMore = response.hasMore
                activity.ui.storeSearchOffset = response.stores.size
                activity.ui.storeSearchError = null
                // Merge into directory cache so product cards reflect latest verified badges
                if (response.stores.isNotEmpty()) {
                    val merged = HashMap(activity.ui.storeDirectoryById)
                    response.stores.forEach { merged[it.id] = it }
                    activity.ui.storeDirectoryById = merged
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                activity.ui.storeSearchError = SupabaseRepository.errorMessage(error)
            } finally {
                activity.ui.storeSearchLoading = false
                activity.ui.invalidate()
            }
        }
    }

    fun loadNextPage(activity: MainActivity) {
        val ui = activity.ui
        if (!SupabaseRepository.hasSession()) return
        if (ui.storeSearchLoading || ui.storeSearchLoadingMore || !ui.storeSearchHasMore) return

        ui.storeSearchLoadingMore = true
        ui.invalidate()
        val q = ui.storeSearchQuery
        val cat = ui.storeSearchCategory
        val offset = ui.storeSearchOffset

        loadMoreJob = activity.authScope.launch {
            try {
                val response = StoreDiscoveryRepository.searchPublicStores(
                    query = q,
                    category = cat,
                    limit = StoreDiscoveryRepository.PAGE_SIZE,
                    offset = offset,
                )
                val existingIds = ui.storeSearchResults.map { it.id }.toHashSet()
                val appended = response.stores.filterNot { it.id in existingIds }
                ui.storeSearchResults = ui.storeSearchResults + appended
                ui.storeSearchTotal = response.total
                ui.storeSearchHasMore = response.hasMore
                ui.storeSearchOffset = ui.storeSearchResults.size
                if (appended.isNotEmpty()) {
                    val merged = HashMap(ui.storeDirectoryById)
                    appended.forEach { merged[it.id] = it }
                    ui.storeDirectoryById = merged
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                android.widget.Toast.makeText(
                    activity,
                    SupabaseRepository.errorMessage(error),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } finally {
                ui.storeSearchLoadingMore = false
                ui.invalidate()
            }
        }
    }

    /** Refreshes the shared store directory used by Home/Explore showcase & product cards. */
    fun refreshStoreDirectory(activity: MainActivity) {
        if (!SupabaseRepository.hasSession()) return
        directoryJob?.cancel()
        directoryJob = activity.authScope.launch {
            val map = StoreDiscoveryRepository.fetchDirectoryMap(limit = 50)
            if (map.isNotEmpty() && map != activity.ui.storeDirectoryById) {
                activity.ui.storeDirectoryById = map
                activity.ui.invalidate()
            }
        }
    }
}
