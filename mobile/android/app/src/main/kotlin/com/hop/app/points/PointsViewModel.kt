package com.hop.app.points

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.repository.PointsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Backs [PointsScreen] -- Phase 2 Slice 2's minimal "visible credit from day
 * one" points tab. Deliberately thin: this class exists only to expose
 * [PointsRepository.observeTotalPoints] as UI state, nothing else. See
 * [PointsScreen]'s own doc for why this stays a single running-total display
 * rather than growing into a profile/settings screen.
 */
class PointsViewModel(pointsRepository: PointsRepository) : ViewModel() {

    val totalPoints: StateFlow<Long> =
        pointsRepository.observeTotalPoints().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
}
