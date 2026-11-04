package at.mikag.lavender.alarm

import at.mikag.lavender.data.Alarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared state between the ringing service and the ringing screen (same process). */
object RingController {
    enum class Sound {
        /** Just started, deciding what to play. */
        STARTING,

        /** TIDAL was asked to play, waiting for music. */
        TIDAL_STARTING,
        TIDAL,

        /** Lavender's own sound: no TIDAL item, or TIDAL didn't start. */
        BACKUP,
    }

    data class Ringing(val alarm: Alarm, val sound: Sound, val tidalFailed: Boolean = false)

    private val _state = MutableStateFlow<Ringing?>(null)
    val state: StateFlow<Ringing?> = _state.asStateFlow()

    /**
     * A TIDAL link the ringing screen should open. Only a visible screen may start another app,
     * so the service asks and the screen does it.
     */
    val launchRequest = MutableStateFlow<String?>(null)

    /**
     * The link the screen last opened. A StateFlow (not an event stream) on purpose: the screen may
     * report it before the service starts waiting, and the report must not get lost.
     */
    val launched = MutableStateFlow<String?>(null)

    /** Whether the ringing screen is currently in front. */
    val screenVisible = MutableStateFlow(false)

    fun start(alarm: Alarm) {
        launchRequest.value = null
        launched.value = null
        _state.value = Ringing(alarm, Sound.STARTING)
    }

    fun setSound(sound: Sound, tidalFailed: Boolean = false) {
        _state.value = _state.value?.copy(sound = sound, tidalFailed = tidalFailed)
    }

    fun stop() {
        launchRequest.value = null
        launched.value = null
        _state.value = null
    }

    fun isRinging(alarmId: Long): Boolean = _state.value?.alarm?.id == alarmId
}
