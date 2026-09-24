package com.onlineteachers.shared
import org.junit.Assert.*
import org.junit.Test
class WireTest {
    @Test fun roundTrip() {
        val f = Frame(1, 2, 7, 123456, byteArrayOf(1, 2, 3))
        val d = Frame.decode(f.encode())
        assertEquals(f.kind, d.kind); assertEquals(f.ptsUs, d.ptsUs)
        assertArrayEquals(f.payload, d.payload)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncation() { Frame.decode(ByteArray(23)) }
    @Test fun permissionsCannotBeBypassed() {
        assertFalse(ActivationPolicy.mayActivate(true, true, true, false))
        assertFalse(ActivationPolicy.mayActivate(true, false, true, true))
        assertFalse(ActivationPolicy.mayActivate(true, true, false, true))
        assertTrue(ActivationPolicy.mayActivate(true, true, true, true))
    }
}
