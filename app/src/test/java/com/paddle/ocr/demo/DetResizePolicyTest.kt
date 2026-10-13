package com.paddle.ocr.demo

import com.paddle.ocr.preprocess.DetResizePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DetResizePolicyTest {
    @Test fun explicitLongSideScalesBothUpAndDownThenCeilsTo128() {
        assertEquals(640 to 1024, DetResizePolicy.explicitLongSize(1080, 1920, 960))
        assertEquals(1024 to 640, DetResizePolicy.explicitLongSize(1920, 1080, 960))
        assertEquals(640 to 1024, DetResizePolicy.explicitLongSize(270, 480, 960))
        assertEquals(1024 to 1024, DetResizePolicy.explicitLongSize(100, 100, 960))
        assertEquals(128 to 1024, DetResizePolicy.explicitLongSize(1, 10000, 960))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidSizeRejected() { DetResizePolicy.explicitLongSize(0, 100, 960) }
}
