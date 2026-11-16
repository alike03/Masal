package com.alike.masal

import com.alike.masal.data.Epg
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** XMLTV times are parsed by hand for speed; check them against java.time. */
class EpgTimeTest {
    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun positiveOffset() = assertEquals(utc("2026-11-16T19:00:00Z"), Epg.parseTime("20261116200000 +0100"))
    @Test fun negativeOffset() = assertEquals(utc("2026-11-17T01:30:00Z"), Epg.parseTime("20261116203000 -0500"))
    @Test fun noOffsetIsUtc() = assertEquals(utc("2026-11-16T20:00:00Z"), Epg.parseTime("20261116200000"))
    @Test fun garbageIsZero() = assertEquals(0L, Epg.parseTime("not a time"))
}
