package com.placeholder.hrvbridge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The heart rate window is orange only when not faint and showing a heart rate or label (as on iOS). */
class DialWindowTest {
    @Test
    fun filledOnlyWhenShowingSomethingAndNotFaint() {
        assertTrue(DialModel(heartRate = 52, hand = DialModel.Hand.INK).windowFilled())
        assertTrue(DialModel.running(hr = 52, handAngle = 400.0, poorContact = true).windowFilled())
        assertFalse(DialModel(label = "no strap", faint = true).windowFilled())
        assertFalse(DialModel(label = "Bluetooth off", faint = true).windowFilled())
        assertFalse(DialModel.running(hr = null, handAngle = 30.0, poorContact = false).windowFilled())
    }
}
