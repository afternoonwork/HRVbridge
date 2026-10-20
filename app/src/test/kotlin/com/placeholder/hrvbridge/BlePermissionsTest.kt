package com.placeholder.hrvbridge

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class BlePermissionsTest {
    @Test
    fun android12AndLaterNeedNearbyDevices() {
        val expected = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(expected, BlePermissions.required(31))
        assertEquals(expected, BlePermissions.required(36))
    }

    @Test
    fun android11AndLowerNeedLocation() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(30))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(26))
    }
}
