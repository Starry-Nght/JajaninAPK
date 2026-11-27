package com.jajanin.app.feature.buyer.store_profile

import com.jajanin.app.MainActivity
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.store_discovery.PublicStoreSummary
import com.jajanin.app.feature.buyer.store_discovery.StoreDiscoveryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel-equivalent coordinator for Public Store Profile inspection (`Screen.STORE_PROFILE`).
 * Buyers can inspect any store without placing an order.
 */
internal object StoreProfileViewModel {
    private var profileJob: Job? = null

    fun openStoreProfile(activity: MainActivity, stallId: String) {
        val cleanId = stallId.trim()
        if (cleanId.isBlank()) return
        if (!SupabaseRepository.hasSession()) {
            android.widget.Toast.makeText(activity, "Masuk dulu untuk melihat profil warung.", android.widget.Toast.LENGTH_SHORT).show()
            activity.showAuthForm(false)
            return
        }
        val ui = activity.ui
        val switchingStore = ui.selectedStoreId != cleanId
        ui.selectedStoreId = cleanId
        if (switchingStore) {
            ui.storeProfileDetail = null
            ui.storeProfileProducts = emptyList()
            ui.storeProfileRatings = emptyList()
            ui.storeProfileCategoryFilter = "All"
            ui.scroll = 0f
        }
        ui.storeProfileError = null
        if (ui.currentScreen != Screen.STORE_PROFILE) {
            ui.go(Screen.STORE_PROFILE)
        }
        loadStoreProfile(activity, cleanId)
    }

    fun loadStoreProfile(activity: MainActivity, stallId: String = activity.ui.selectedStoreId.orEmpty()) {
        val cleanId = stallId.trim()
        if (cleanId.isBlank() || !SupabaseRepository.hasSession()) return
        profileJob?.cancel()
        val ui = activity.ui
        ui.storeProfileLoading = ui.storeProfileDetail == null
        ui.storeProfileError = null
        ui.invalidate()

        profileJob = activity.authScope.launch {
            try {
                val payload = StoreDiscoveryRepository.fetchPublicStoreProfile(cleanId)
                ui.storeProfileDetail = payload.stall
                ui.storeProfileProducts = payload.products
                ui.storeProfileRatings = payload.recentRatings
                ui.storeProfileError = null

                // Keep directory cache synchronized with fresh store metadata & verified status
                val summary = PublicStoreSummary(
                    id = payload.stall.id,
                    name = payload.stall.name,
                    bio = payload.stall.bio,
                    storeCategory = payload.stall.storeCategory,
                    location = payload.stall.location,
                    isOpen = payload.stall.isOpen,
                    isVerified = payload.stall.isVerified,
                    verifiedAt = payload.stall.verifiedAt,
                    completedOrdersCount = payload.stall.completedOrdersCount,
                    logoUrl = payload.stall.logoUrl,
                    bannerUrl = payload.stall.bannerUrl,
                    sellerName = payload.stall.sellerName,
                    activeProductCount = payload.products.size,
                    minPriceIdr = payload.products.minOfOrNull { it.priceIdr },
                    avgRating = payload.stall.avgRating,
                    ratingCount = payload.stall.ratingCount,
                )
                val updatedMap = HashMap(ui.storeDirectoryById)
                updatedMap[summary.id] = summary
                ui.storeDirectoryById = updatedMap
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                ui.storeProfileError = SupabaseRepository.errorMessage(error)
            } finally {
                ui.storeProfileLoading = false
                ui.invalidate()
            }
        }
    }

    fun selectCategoryFilter(activity: MainActivity, category: String) {
        if (activity.ui.storeProfileCategoryFilter == category) return
        activity.ui.storeProfileCategoryFilter = category
        activity.ui.invalidate()
    }
}
