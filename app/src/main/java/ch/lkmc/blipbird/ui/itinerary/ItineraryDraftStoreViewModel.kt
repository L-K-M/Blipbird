package ch.lkmc.blipbird.ui.itinerary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.model.TransitionIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ItineraryDraftLeg(
    val rowId: String = UUID.randomUUID().toString(),
    val existingFlightId: Long? = null,
    val originalMemberFlightId: Long? = null,
    val replacesFlightId: Long? = null,
    val designator: String = "",
    val dateLocal: String = "",
    val dateConfirmed: Boolean = false,
    val transitionAfter: String = TransitionIntent.UNKNOWN.name,
)

@Serializable
data class ItineraryDraft(
    val draftId: String,
    val itineraryId: Long? = null,
    val name: String = "",
    val legs: List<ItineraryDraftLeg> = listOf(ItineraryDraftLeg(), ItineraryDraftLeg()),
    val deleteRemovedFlightIds: List<Long> = emptyList(),
    val dirty: Boolean = false,
)

class ItineraryDraftStoreViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _drafts = MutableStateFlow(load())
    val drafts: StateFlow<Map<String, ItineraryDraft>> = _drafts.asStateFlow()

    fun draft(id: String): ItineraryDraft? = _drafts.value[id]

    /** Returns false rather than silently truncating an oversized saved draft. */
    fun put(draft: ItineraryDraft): Boolean {
        val encoded = json.encodeToString(draft)
        if (encoded.toByteArray(Charsets.UTF_8).size > ItineraryRepository.MAX_SAVED_DRAFT_BYTES) {
            return false
        }
        _drafts.value = _drafts.value + (draft.draftId to draft)
        persist()
        return true
    }

    fun remove(id: String) {
        _drafts.value = _drafts.value - id
        persist()
    }

    private fun persist() {
        savedStateHandle[KEY_DRAFTS] = ArrayList(
            _drafts.value.values.map { json.encodeToString(it) }
        )
    }

    private fun load(): Map<String, ItineraryDraft> =
        savedStateHandle.get<ArrayList<String>>(KEY_DRAFTS).orEmpty()
            .mapNotNull { encoded -> runCatching { json.decodeFromString<ItineraryDraft>(encoded) }.getOrNull() }
            .associateBy { it.draftId }

    private companion object {
        const val KEY_DRAFTS = "itinerary_drafts"
    }
}
