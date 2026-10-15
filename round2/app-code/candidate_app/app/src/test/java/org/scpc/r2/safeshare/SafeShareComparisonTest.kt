package org.scpc.r2.safeshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeShareComparisonTest {
    @Test
    fun threeSurfacesUseByteIdenticalSnapshotsAndExposeLateRawDeltas() {
        val stores = linkedMapOf<String, RelayStateStore>()
        val comparison = SafeShareComparison(
            storeForNamespace = { namespace ->
                stores.getOrPut(namespace) { InMemoryRelayStateStore() }
            },
            runId = "unit-test-run",
            virtualClockMillis = 1_722_342_600_000L,
        ).run()

        assertEquals("safeshare-comparison-v3", comparison.getString("schema_version"))
        assertEquals(6, stores.size)
        val pairs = comparison.getJSONArray("pairs")
        assertEquals(3, pairs.length())
        for (index in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(index)
            val full = pair.getJSONObject("full")
            val claimOff = pair.getJSONObject("claim_off")
            val fullMetrics = full.getJSONObject("metrics")
            val claimOffMetrics = claimOff.getJSONObject("metrics")
            val delta = pair.getJSONObject("observed_delta")

            assertNotEquals(full.getString("namespace"), claimOff.getString("namespace"))
            assertEquals(
                pair.getString("initial_snapshot_sha256"),
                pair.getString("full_initial_snapshot_sha256"),
            )
            assertEquals(
                pair.getString("initial_snapshot_sha256"),
                pair.getString("claim_off_initial_snapshot_sha256"),
            )
            assertTrue(pair.getInt("claim_off_initial_state_key_count") > 0)
            assertEquals(
                pair.getString("full_state_sha256_before_claim_off"),
                pair.getString("full_state_sha256_after_claim_off"),
            )
            assertEquals(3, fullMetrics.getInt("initial_active_draft_count"))
            assertEquals(3, claimOffMetrics.getInt("initial_active_draft_count"))
            assertEquals(1, fullMetrics.getInt("e3_affected_draft_count"))
            assertEquals(3, claimOffMetrics.getInt("e3_affected_draft_count"))
            assertEquals("DEPENDENCY_SCOPED", fullMetrics.getString("e3_impact_selection_mode"))
            assertEquals("PURPOSE_WIDE", claimOffMetrics.getString("e3_impact_selection_mode"))
            assertEquals(1, fullMetrics.getInt("e4_invalidated_draft_count"))
            assertEquals(3, claimOffMetrics.getInt("e4_invalidated_draft_count"))
            assertEquals(1, fullMetrics.getInt("e4_replacement_draft_count"))
            assertEquals(3, claimOffMetrics.getInt("e4_replacement_draft_count"))
            assertEquals(0, fullMetrics.getInt("late_route_confirmation_required_count"))
            assertEquals(1, claimOffMetrics.getInt("late_route_confirmation_required_count"))
            assertEquals(2, delta.getInt("e3_affected_draft_count"))
            assertEquals(2, delta.getInt("e4_invalidated_draft_count"))
            assertEquals(2, delta.getInt("e4_replacement_draft_count"))
            assertEquals(1, delta.getInt("late_route_confirmation_required_count"))
            assertEquals(1, fullMetrics.getInt("duplicate_event_count"))
            assertEquals(1, claimOffMetrics.getInt("duplicate_event_count"))
            assertEquals(0, fullMetrics.getInt("duplicate_commit_count"))
            assertEquals(0, claimOffMetrics.getInt("duplicate_commit_count"))
            assertEquals(0, fullMetrics.getInt("external_dispatch_count"))
            assertEquals(0, claimOffMetrics.getInt("external_dispatch_count"))
            assertEquals(1, fullMetrics.getInt("process_epoch"))
            assertEquals(1, claimOffMetrics.getInt("process_epoch"))
        }

        val aggregate = comparison.getJSONObject("aggregate")
        assertEquals(3, aggregate.getInt("pair_count"))
        assertEquals(3, aggregate.getInt("full_e3_affected_draft_count"))
        assertEquals(9, aggregate.getInt("claim_off_e3_affected_draft_count"))
        assertEquals(0, aggregate.getInt("full_late_route_confirmation_required_count"))
        assertEquals(3, aggregate.getInt("claim_off_late_route_confirmation_required_count"))
        assertEquals(0, aggregate.getInt("full_external_dispatch_count"))
        assertEquals(0, aggregate.getInt("claim_off_external_dispatch_count"))
    }

    @Test
    fun comparisonOutputContainsNoEvaluationKeys() {
        val comparison = SafeShareComparison(
            storeForNamespace = { InMemoryRelayStateStore() },
            runId = "no-evaluation-keys",
            virtualClockMillis = 1_722_342_600_000L,
        ).run()
        val encoded = comparison.toString()
        listOf("pass", "fail", "expected", "verdict", "score", "anchor").forEach { key ->
            assertFalse(Regex("\\\"$key\\\"\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(encoded))
        }
        assertTrue(comparison.getJSONArray("pairs").length() == 3)
    }
}
