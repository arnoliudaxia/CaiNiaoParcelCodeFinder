package com.paddle.ocr.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionScaleTest {
    @Test fun scalesDimensions() {
        assertEquals(1920, ResolutionScale.ORIGINAL.dimension(1920))
        assertEquals(960, ResolutionScale.HALF.dimension(1920))
        assertEquals(480, ResolutionScale.QUARTER.dimension(1920))
        assertEquals(270, ResolutionScale.QUARTER.dimension(1080))
    }

    @Test fun floorsOddDimensionsAndKeepsAtLeastOnePixel() {
        assertEquals(50, ResolutionScale.HALF.dimension(101))
        assertEquals(25, ResolutionScale.QUARTER.dimension(101))
        assertEquals(1, ResolutionScale.QUARTER.dimension(3))
        assertEquals(1, ResolutionScale.HALF.dimension(1))
    }
}
