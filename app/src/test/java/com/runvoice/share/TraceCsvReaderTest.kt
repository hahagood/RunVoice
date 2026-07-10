package com.runvoice.share

import org.junit.Assert.assertEquals
import org.junit.Test

class TraceCsvReaderTest {
    @Test fun parsesQuotedCsvFields() {
        assertEquals(
            listOf("accepted", "reason,with,commas", "a\"b"),
            TraceCsvReader().parseLine("accepted,\"reason,with,commas\",\"a\"\"b\"")
        )
    }
}
