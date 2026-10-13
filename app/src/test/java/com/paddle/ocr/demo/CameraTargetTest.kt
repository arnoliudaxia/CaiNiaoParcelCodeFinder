package com.paddle.ocr.demo

import org.junit.Assert.*
import org.junit.Test

class CameraTargetTest {
    @Test fun exactAsciiAndLeadingZeros() {
        assertTrue(CameraTarget.isValid("0007"))
        listOf("", "123", "12345", "１２３４", "12a4", " 1234").forEach {
            assertFalse(CameraTarget.isValid(it))
        }
        assertEquals(setOf(0, 3), findCameraTargetHit("0007", listOf("0007", "7", "00070", " 0007\n"))!!.indices)
    }
    @Test fun noTargetNoSubstringAndMultipleHits() {
        val texts = listOf("1234", "12345", "A1234", "1234B", "1234", "12 34")
        assertNull(findCameraTargetHit(null, texts))
        assertNull(findCameraTargetHit("", texts))
        assertNull(findCameraTargetHit("5678", texts))
        assertEquals(CameraTargetHit("1234", setOf(0, 4)), findCameraTargetHit("1234", texts))
    }
    @Test fun explicitApplyRejectsInvalidWithoutChangingActiveAndClearResetsBoth() {
        val initial = CameraTarget().edit("0007")
        assertNull(initial.active)
        val applied = initial.apply()
        assertEquals("0007", applied.active)
        val invalid = applied.edit("12345").apply()
        assertEquals("0007", invalid.active)
        assertNotNull(invalid.error)
        assertNull(invalid.edit("").apply().active)
    }
    @Test fun publishedHitIsIndependentOfLaterTargetChanges() {
        val state = CameraTarget("0007").apply()
        val hit = findCameraTargetHit(state.active, listOf("0007"))!!
        assertEquals("1234", state.edit("1234").apply().active)
        assertEquals("0007", hit.target)
        assertEquals(setOf(0), hit.indices)
    }
}
