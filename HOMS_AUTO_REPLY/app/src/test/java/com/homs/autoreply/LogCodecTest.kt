package com.homs.autoreply

import org.junit.Assert.assertEquals
import org.junit.Test

class LogCodecTest {
    @Test
    fun roundTrip() {
        val list = listOf(LogEntry(1000L, "تم الرد"), LogEntry(2000L, "انتهت المكالمة قبل الرد"))
        assertEquals(list, LogCodec.decode(LogCodec.encode(list)))
    }

    @Test
    fun badInputGivesEmpty() {
        assertEquals(emptyList<LogEntry>(), LogCodec.decode("not json"))
        assertEquals(emptyList<LogEntry>(), LogCodec.decode(null))
    }
}
