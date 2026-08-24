package com.lunov.flyshare.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryTest {

    private fun entry(id: String, outcome: Outcome = Outcome.Complete) = HistoryEntry(
        id = id,
        outgoing = false,
        peerName = "MSI",
        fileCount = 3,
        totalSize = 1_000,
        transferred = 1_000,
        outcome = outcome,
        startedAt = 1_000,
        finishedAt = 3_000,
        destination = "Download/FlyShare",
    )

    @Test
    fun `newest first, and it survives a restart`() {
        val storage = MemoryStorage()
        History(storage).apply {
            record(entry("one"))
            record(entry("two"))
        }

        val reopened = History(storage).entries.value
        assertEquals(listOf("two", "one"), reopened.map { it.id })
        assertEquals("Download/FlyShare", reopened.first().destination)
        assertEquals(Outcome.Complete, reopened.first().outcome)
    }

    @Test
    fun `settling twice does not record a transfer twice`() {
        // The state machine can emit a final state more than once on its way
        // down; the record has to be of the transfer, not of the emission.
        val history = History(MemoryStorage())
        history.record(entry("one"))
        history.record(entry("one", Outcome.Failed))

        assertEquals(1, history.entries.value.size)
        assertEquals(Outcome.Failed, history.entries.value.single().outcome)
    }

    @Test
    fun `it stops growing`() {
        val history = History(MemoryStorage(), limit = 3)
        repeat(10) { history.record(entry("t$it")) }

        assertEquals(3, history.entries.value.size)
        assertEquals(listOf("t9", "t8", "t7"), history.entries.value.map { it.id })
    }

    @Test
    fun `average rate comes from the time it actually ran`() {
        val done = entry("one").copy(transferred = 2_000, startedAt = 1_000, finishedAt = 3_000)
        assertEquals(1_000.0, done.averageRate)

        // Never started: no rate to report rather than a division by zero.
        assertEquals(null, done.copy(startedAt = 0, finishedAt = 0).averageRate)
    }

    @Test
    fun `unreadable storage is empty history, not a crash`() {
        val storage = MemoryStorage()
        storage.write("history.json", "{ this is not json")
        assertTrue(History(storage).entries.value.isEmpty())
    }
}
