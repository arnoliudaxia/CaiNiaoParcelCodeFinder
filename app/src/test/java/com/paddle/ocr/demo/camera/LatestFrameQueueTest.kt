package com.paddle.ocr.demo.camera

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LatestFrameQueueTest {
    private class FakeFrame(val id: Int) {
        val closes = AtomicInteger()
    }

    @Test
    fun capacityIsOne_secondFrameIsReleasedNotBuffered() {
        val released = mutableListOf<Int>()
        val queue = LatestFrameQueue<FakeFrame>(1) { released += it.id; it.closes.incrementAndGet() }
        assertTrue(queue.offer(FakeFrame(1)))
        val second = FakeFrame(2)
        assertFalse(queue.offer(second))
        assertEquals(listOf(2), released)
        assertEquals(1, second.closes.get())
    }

    @Test
    fun receivedFrameReleaseIsIdempotent() = runTest {
        val first = FakeFrame(1)
        val queue = LatestFrameQueue<FakeFrame>(1) { it.closes.incrementAndGet() }
        queue.offer(first)
        val frame = queue.receive()
        frame.release()
        frame.release()
        assertEquals(1, first.closes.get())
    }

    @Test
    fun closeReleasesBufferedFrameAndRejectsLaterOffers() = runTest {
        val buffered = FakeFrame(1)
        val late = FakeFrame(2)
        val queue = LatestFrameQueue<FakeFrame>(1) { it.closes.incrementAndGet() }
        queue.offer(buffered)
        queue.close()
        queue.close()
        assertEquals(1, buffered.closes.get())
        assertFalse(queue.offer(late))
        assertEquals(1, late.closes.get())
        try {
            queue.receive()
            fail("receive after close must throw")
        } catch (expected: ClosedReceiveChannelException) {
        }
    }

    @Test
    fun cancelledWaitingReceiverDoesNotLeakLaterFrames() = runTest {
        val queue = LatestFrameQueue<FakeFrame>(1) { it.closes.incrementAndGet() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { queue.receive() }
        job.cancel()
        job.join()
        val frame = FakeFrame(1)
        queue.offer(frame)
        queue.close()
        assertEquals(1, frame.closes.get())
    }

    /** Analyzer thread offers while a consumer processes and close races both. */
    @Test
    fun concurrentOfferReceiveCloseReleasesEveryFrameExactlyOnce() = runBlocking {
        repeat(200) { round ->
            val frames = Collections.synchronizedList(mutableListOf<FakeFrame>())
            val releasedIds = ConcurrentHashMap.newKeySet<Int>()
            val queue = LatestFrameQueue<FakeFrame>(round.toLong()) {
                it.closes.incrementAndGet()
                releasedIds += it.id
            }
            val producer = launch(Dispatchers.Default) {
                for (i in 0 until 100) {
                    val f = FakeFrame(i)
                    frames += f
                    queue.offer(f)
                }
            }
            val consumer = launch(Dispatchers.Default) {
                try {
                    while (true) queue.receive().release()
                } catch (_: ClosedReceiveChannelException) {
                }
            }
            launch(Dispatchers.Default) { queue.close() }.join()
            producer.join()
            queue.close()
            consumer.cancel()
            consumer.join()
            queue.close()
            frames.forEach { assertEquals("frame ${it.id} round $round", 1, it.closes.get()) }
            assertEquals(frames.size, releasedIds.size)
        }
    }
}
