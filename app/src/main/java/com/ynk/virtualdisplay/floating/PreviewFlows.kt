package com.ynk.virtualdisplay.floating

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** Capsule / PiP preview: at most [PREVIEW_FPS] frames per second, keep only the newest. */
const val PREVIEW_FPS = 5

/**
 * Derived from Mika CapsulePreview.kt (Apache-2.0).
 * Emits at most one frame per [intervalMs]; intermediate values are dropped via conflate.
 */
fun <T : Any> Flow<T>.previewFrames(
    intervalMs: Long = 1_000L / PREVIEW_FPS,
    same: (T, T) -> Boolean = { a, b -> a === b },
): Flow<T> = flow {
    var last: T? = null
    conflate().collect { value ->
        val prev = last
        if (prev != null && (prev === value || same(prev, value))) return@collect
        last = value
        emit(value)
        delay(intervalMs)
    }
}

