package com.bishnu.stresswelfarebridge.camera

import com.bishnu.stresswelfarebridge.ppg.CameraHrvResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the most recent camera-derived HRV measurement.
 *
 * Kept strictly separate from Health Connect: this repository only ever exposes camera results and
 * never reads or writes Health Connect records, so a camera measurement cannot overwrite a wearable
 * HRV value (and vice versa).
 */
object CameraHrvRepository {

    private val _latest = MutableStateFlow<CameraHrvResult?>(null)

    /** Most recent completed camera measurement, or null if none has been taken this session. */
    val latest: StateFlow<CameraHrvResult?> = _latest.asStateFlow()

    fun publish(result: CameraHrvResult) {
        _latest.value = result
    }

    fun clear() {
        _latest.value = null
    }
}