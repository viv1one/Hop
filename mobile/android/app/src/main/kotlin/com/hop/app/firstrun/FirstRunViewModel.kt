package com.hop.app.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.crypto.AttestationProvider
import com.hop.crypto.AttestationResult
import com.hop.data.SettingsRepository
import com.hop.protocol.ReachTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * First-run setup: pick a default reach tier, silently attest the device,
 * persist both, and signal navigation to the main screen.
 *
 * Device attestation here is silent background plumbing (PRD §5, ADR 0004) --
 * there is no UI for the attest() call itself, and first-run setup proceeds
 * identically whether it passes or fails, except for a one-line fallback
 * message on [AttestationResult.Failed] (per the "no user-facing prompt
 * beyond a fallback message" requirement). The attestation result is not
 * otherwise acted on in this slice -- no real Play Integrity backing exists
 * yet ([com.hop.crypto.StubAttestationProvider] always passes), and nothing
 * downstream (faucets/rate-limits/"don't relay"/blocking) is wired to consume
 * it yet.
 */
class FirstRunViewModel(
    private val settingsRepository: SettingsRepository,
    private val attestationProvider: AttestationProvider,
) : ViewModel() {

    data class UiState(
        val selectedTier: ReachTier = ReachTier.LOCALITY,
        val isSubmitting: Boolean = false,
        val attestationFailedMessage: String? = null,
        val navigateToMain: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onTierSelected(tier: ReachTier) {
        _uiState.update { it.copy(selectedTier = tier, attestationFailedMessage = null) }
    }

    fun onContinueClicked() {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, attestationFailedMessage = null) }

        viewModelScope.launch {
            val nonce = ByteArray(NONCE_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            val result = attestationProvider.attest(nonce)

            // Proceed regardless of pass/fail -- attestation failure isn't a
            // first-run blocker in this slice (no enforcement is wired to it
            // yet); only surface the fallback message.
            val failureMessage = (result as? AttestationResult.Failed)?.reason

            val tier = _uiState.value.selectedTier
            settingsRepository.setDefaultReachTier(tier)
            settingsRepository.setHasCompletedFirstRun(true)

            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    attestationFailedMessage = failureMessage,
                    navigateToMain = true,
                )
            }
        }
    }

    private companion object {
        const val NONCE_SIZE_BYTES = 32
    }
}
