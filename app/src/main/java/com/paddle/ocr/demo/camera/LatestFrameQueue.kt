package com.paddle.ocr.demo.camera

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands at most one camera frame from the analyzer thread to the OCR coroutine.
 *
 * This is not a frame cache: capacity is one, and a frame that cannot be queued is released
 * immediately. CameraX KEEP_ONLY_LATEST does not deliver another frame until the held one is
 * released, so frames arriving while OCR is busy are skipped by CameraX instead of piling up.
 *
 * Every offered frame is released exactly once, whether it is processed, rejected, left in the
 * buffer at [close], or lost by a cancelled [receive].
 */
class LatestFrameQueue<T : Any>(
    val sessionId: Long,
    private val release: (T) -> Unit,
) {
    /** Idempotent ownership wrapper; release can be reached from several cancellation paths. */
    class Frame<T : Any> internal constructor(val value: T, private val release: (T) -> Unit) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) release(value)
        }
    }

    private val channel = Channel<Frame<T>>(capacity = 1) { it.release() }

    /** Takes ownership of [item] in all cases. Returns false if it was released instead of queued. */
    fun offer(item: T): Boolean {
        val frame = Frame(item, release)
        val queued = try {
            channel.trySend(frame).isSuccess
        } catch (t: Throwable) {
            false
        }
        if (!queued) frame.release()
        return queued
    }

    /** Suspends for the next frame; the caller must call [Frame.release] when finished. */
    suspend fun receive(): Frame<T> = channel.receive()

    /** Rejects later offers and releases any buffered frame. Safe to call repeatedly. */
    fun close() {
        channel.close()
        while (true) {
            val pending = channel.tryReceive().getOrNull() ?: break
            pending.release()
        }
    }
}
