package org.scpc.r2.safeshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

class SafeShareOfficialSequenceTest {
    @Test
    fun publicThirteenStepSequenceDeletesAllHistoricalOneOffRawContent() {
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "process-public-a")
        val observations = mutableListOf<JSONObject>()
        observations += core.execute(step(1, "RESET_AND_START"))
        observations += core.execute(
            step(
                2,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to "g.1",
                    "TARGET_ENTITY" to "n.1",
                    "STABLE_VALUE" to "v.stable",
                    "ONE_OFF_VALUE" to "v.once.old",
                    "CURRENT_AUTHORITY" to "a.1",
                    "PRESERVED_SCOPE" to "s.keep",
                ),
                "session-a",
            ),
        )
        observations += core.execute(
            step(
                3,
                "ADVANCE_SESSION",
                roles(
                    "PRIMARY_GOAL" to "g.1",
                    "TARGET_ENTITY" to "n.1",
                    "DISTRACTOR_ENTITY" to "n.2",
                ),
                "session-b",
            ),
        )
        val originalDecision = core.execute(
            step(
                4,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "g.1", "TARGET_ENTITY" to "n.1"),
                "session-b",
                "decision-original",
            ),
        )
        observations += originalDecision
        val originalAction = originalDecision.getJSONObject("action")
        observations += core.execute(
            step(
                5,
                "CORRECT_FACT",
                roles(
                    "PRIMARY_GOAL" to "g.1",
                    "TARGET_ENTITY" to "n.1",
                    "ONE_OFF_VALUE" to "v.once.new",
                    "CURRENT_AUTHORITY" to "a.2",
                    "PRESERVED_SCOPE" to "s.keep",
                ),
                "session-b",
                "correction-current",
            ),
        )
        observations += core.execute(
            step(
                6,
                "REVOKE_SCOPE",
                roles("PRESERVED_SCOPE" to "s.keep", "REVOKED_SCOPE" to "s.drop"),
                "session-b",
            ),
        )
        val deleted = core.execute(
            step(7, "DELETE_FACT", roles("PRIMARY_GOAL" to "g.1", "TARGET_ENTITY" to "n.1"), "session-b"),
        )
        observations += deleted
        assertTrue(deleted.getJSONArray("invalidated_state_ids").length() >= 2)
        assertFalse(store.load().getJSONObject("facts").toString().contains("v.once.old"))
        assertFalse(store.load().getJSONObject("facts").toString().contains("v.once.new"))
        assertTrue(deleted.getJSONArray("preserved_state_ids").toString().contains("stable"))

        observations += core.execute(
            step(
                8,
                "SET_NETWORK",
                roles("NETWORK_STATE" to "OFFLINE", "ONE_OFF_VALUE" to "ONLINE"),
                "session-b",
            ),
        )
        core = SafeShareCore(store, "process-public-b")
        val relaunched = core.execute(
            step(9, "PROCESS_KILL_RELAUNCH", sessionId = "session-c"),
        )
        observations += relaunched
        assertEquals(1, relaunched.getInt("process_epoch"))
        assertEquals("OFFLINE", relaunched.getString("network_state"))
        assertEquals("ABSTAIN", relaunched.getString("decision_state"))

        val replay = core.execute(
            step(
                10,
                "REPLAY_EVENT",
                roles("REPLAY_OF_EVENT_ID" to "decision-original"),
                "session-c",
            ),
        )
        observations += replay
        assertEquals(originalAction.getString("action_id"), replay.getJSONObject("action").getString("action_id"))
        assertEquals(
            originalAction.getString("idempotency_key"),
            replay.getJSONObject("action").getString("idempotency_key"),
        )
        observations += core.execute(
            step(
                11,
                "DELIVER_OUT_OF_ORDER",
                roles("OLDER_EVENT_ID" to "fact-original"),
                "session-c",
            ),
        )
        val delayed = core.execute(
            step(
                12,
                "ADVANCE_TIME",
                roles("DELAYED_OUTCOME" to "outcome.1"),
                "session-c",
            ),
        )
        observations += delayed
        observations += core.execute(step(13, "EXPORT_AND_END", sessionId = "session-c"))

        assertEquals(13, observations.size)
        assertEquals("OFFLINE", delayed.getString("network_state"))
        observations.forEach {
            assertEquals(64, it.getString("state_before_sha256").length)
            assertEquals(64, it.getString("state_after_sha256").length)
        }
    }

    @Test
    fun practiceASequenceRecoversTheSameCorrectionActionAfterRelaunch() {
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "process-a-1")
        core.execute(step(1, "RESET_AND_START"))
        core.execute(
            step(
                2,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.a",
                    "TARGET_ENTITY" to "node.a",
                    "STABLE_VALUE" to "value.a1",
                    "ONE_OFF_VALUE" to "once.a",
                    "CURRENT_AUTHORITY" to "auth.a1",
                    "PRESERVED_SCOPE" to "scope.a",
                ),
                "a-session-1",
            ),
        )
        core.execute(
            step(
                3,
                "ADVANCE_SESSION",
                roles(
                    "PRIMARY_GOAL" to "goal.a",
                    "TARGET_ENTITY" to "node.a",
                    "DISTRACTOR_ENTITY" to "node.b",
                ),
                "a-session-2",
            ),
        )
        val firstDecision = core.execute(
            step(
                4,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "goal.a", "TARGET_ENTITY" to "node.a"),
                "a-session-2",
            ),
        )
        assertFalse(firstDecision.getJSONArray("selected_context_ids").toString().contains("node.b"))
        val correction = core.execute(
            step(
                5,
                "CORRECT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.a",
                    "TARGET_ENTITY" to "node.a",
                    "STABLE_VALUE" to "value.a2",
                    "CURRENT_AUTHORITY" to "auth.a2",
                    "PRESERVED_SCOPE" to "scope.a",
                ),
                "a-session-3",
                "correction-a",
            ),
        )
        val proposed = correction.getJSONObject("action")
        val revoked = core.execute(
            step(
                6,
                "REVOKE_SCOPE",
                roles("PRESERVED_SCOPE" to "scope.a", "REVOKED_SCOPE" to "scope.b"),
                "a-session-3",
            ),
        )
        assertEquals(1, revoked.getJSONArray("invalidated_state_ids").length())

        core = SafeShareCore(store, "process-a-2")
        assertEquals(
            "PENDING",
            core.execute(step(7, "PROCESS_KILL_RELAUNCH", sessionId = "a-session-4"))
                .getString("decision_state"),
        )
        val recovered = core.execute(
            step(
                8,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "goal.a", "TARGET_ENTITY" to "node.a"),
                "a-session-4",
            ),
        ).getJSONObject("action")
        assertEquals(proposed.getString("action_id"), recovered.getString("action_id"))
        assertEquals(proposed.getString("idempotency_key"), recovered.getString("idempotency_key"))
        assertEquals("COMMITTED", recovered.getString("commit_state"))
        core.execute(step(9, "EXPORT_AND_END", sessionId = "a-session-4"))
    }

    @Test
    fun practiceBConsumesRegisteredDelayedOutcomeAndInvalidatesItWithItsSource() {
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "process-b-1")
        core.execute(step(1, "RESET_AND_START"))
        val registered = core.execute(
            step(
                2,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.b",
                    "TARGET_ENTITY" to "node.c",
                    "STABLE_VALUE" to "value.b",
                    "ONE_OFF_VALUE" to "once.b",
                    "CURRENT_AUTHORITY" to "auth.b",
                    "PRESERVED_SCOPE" to "scope.b",
                    "DELAYED_OUTCOME" to "SYNTHETIC_REVIEW_PENDING",
                ),
                "b-session-1",
            ),
        )
        val pendingId = (0 until registered.getJSONArray("preserved_state_ids").length())
            .map(registered.getJSONArray("preserved_state_ids")::getString)
            .single { it.startsWith("pending-outcome-") }
        core.execute(
            step(
                3,
                "SET_NETWORK",
                roles("NETWORK_STATE" to "OFFLINE", "ONE_OFF_VALUE" to "ONLINE"),
                "b-session-1",
            ),
        )
        val offline = core.execute(
            step(
                4,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "goal.b", "TARGET_ENTITY" to "node.c"),
                "b-session-1",
                "decision-b",
            ),
        )
        assertEquals("WAIT", offline.getString("decision_state"))
        assertTrue(offline.isNull("action"))
        core.execute(
            step(
                5,
                "SET_NETWORK",
                roles("NETWORK_STATE" to "ONLINE", "ONE_OFF_VALUE" to "OFFLINE"),
                "b-session-2",
            ),
        )
        assertEquals(
            "WAIT",
            core.execute(
                step(
                    6,
                    "REPLAY_EVENT",
                    roles("REPLAY_OF_EVENT_ID" to "decision-b"),
                    "b-session-2",
                ),
            ).getString("decision_state"),
        )
        core.execute(
            step(
                7,
                "DELIVER_OUT_OF_ORDER",
                roles("OLDER_EVENT_ID" to "old-b"),
                "b-session-2",
            ),
        )
        val delivered = core.execute(
            step(
                8,
                "ADVANCE_TIME",
                roles("DELAYED_OUTCOME" to "SYNTHETIC_REVIEW_ACCEPTED"),
                "b-session-2",
            ),
        )
        val outcomeId = "outcome-${SafeShareId.component("event-8")}"
        assertEquals("PENDING", delivered.getString("decision_state"))
        assertTrue(delivered.getJSONArray("invalidated_state_ids").toString().contains(pendingId))
        val deliveredState = store.load().getJSONObject("delayed_outcomes").getJSONObject(outcomeId)
        assertEquals("SYNTHETIC_REVIEW_ACCEPTED", deliveredState.getString("value"))
        assertEquals("event-2", deliveredState.getString("registered_by_event_id"))
        assertEquals(
            "evidence-${SafeShareId.component("event-2")}",
            deliveredState.getString("registered_evidence_id"),
        )
        assertEquals(2, deliveredState.getJSONArray("dependency_ids").length())
        val deleted = core.execute(
            step(9, "DELETE_FACT", roles("TARGET_ENTITY" to "node.c"), "b-session-2"),
        )
        assertTrue(deleted.getJSONArray("invalidated_state_ids").length() >= 2)
        assertTrue(deleted.getJSONArray("invalidated_state_ids").toString().contains(outcomeId))
        assertFalse(store.load().getJSONObject("facts").toString().contains("once.b"))
        val invalidatedOutcome = store.load().getJSONObject("delayed_outcomes").getJSONObject(outcomeId)
        assertEquals("INVALIDATED", invalidatedOutcome.getString("status"))
        assertFalse(invalidatedOutcome.has("value"))

        val staleOutcomeReplay = core.execute(
            step(
                10,
                "ADVANCE_TIME",
                roles("DELAYED_OUTCOME" to "SYNTHETIC_REVIEW_ACCEPTED"),
                "b-session-2",
            ),
        )
        assertEquals("ABSTAIN", staleOutcomeReplay.getString("decision_state"))

        core = SafeShareCore(store, "process-b-2")
        val relaunched = core.execute(
            step(11, "PROCESS_KILL_RELAUNCH", sessionId = "b-session-3"),
        )
        assertEquals(1, relaunched.getInt("process_epoch"))
        assertEquals("NO_DECISION", relaunched.getString("decision_state"))
        val exported = core.execute(step(12, "EXPORT_AND_END", sessionId = "b-session-3"))
        assertTrue(exported.getJSONArray("preserved_state_ids").toString().contains("stable"))
        assertFalse(exported.getJSONArray("preserved_state_ids").toString().contains(outcomeId))
    }

    @Test
    fun delayedOutcomeIsSelectedOnlyForItsGoalAndEntity() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-scope")
        core.execute(step(1, "RESET_AND_START"))
        core.execute(
            step(
                2,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.1",
                    "TARGET_ENTITY" to "node.1",
                    "STABLE_VALUE" to "value.1",
                    "CURRENT_AUTHORITY" to "authority.1",
                ),
            ),
        )
        core.execute(
            step(
                3,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "goal.1", "TARGET_ENTITY" to "node.1"),
            ),
        )
        core.execute(
            step(4, "ADVANCE_TIME", roles("DELAYED_OUTCOME" to "outcome.scoped")),
        )
        core.execute(
            step(
                5,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.2",
                    "TARGET_ENTITY" to "node.2",
                    "STABLE_VALUE" to "value.2",
                    "CURRENT_AUTHORITY" to "authority.2",
                ),
            ),
        )

        val other = core.execute(
            step(
                6,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "goal.2", "TARGET_ENTITY" to "node.2"),
            ),
        )
        assertEquals(1, other.getJSONArray("selected_context_ids").length())
        assertFalse(other.getJSONArray("selected_context_ids").toString().contains("outcome-"))

        val original = core.execute(
            step(
                7,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to "goal.1", "TARGET_ENTITY" to "node.1"),
            ),
        )
        assertEquals(2, original.getJSONArray("selected_context_ids").length())
        assertTrue(original.getJSONArray("selected_context_ids").toString().contains("outcome-"))
    }

    @Test
    fun conflictingPreservedAndRevokedScopeAbstainsWithoutChangingState() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-conflict")
        core.execute(step(1, "RESET_AND_START"))
        core.execute(
            step(
                2,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.c",
                    "TARGET_ENTITY" to "node.c",
                    "STABLE_VALUE" to "value.c1",
                    "CURRENT_AUTHORITY" to "authority.c1",
                    "PRESERVED_SCOPE" to "scope.same",
                ),
            ),
        )
        core.execute(
            step(
                3,
                "CORRECT_FACT",
                roles(
                    "PRIMARY_GOAL" to "goal.c",
                    "TARGET_ENTITY" to "node.c",
                    "STABLE_VALUE" to "value.c2",
                    "CURRENT_AUTHORITY" to "authority.c2",
                    "PRESERVED_SCOPE" to "scope.same",
                ),
            ),
        )
        val result = core.execute(
            step(
                4,
                "REVOKE_SCOPE",
                roles(
                    "PRIMARY_GOAL" to "goal.c",
                    "TARGET_ENTITY" to "node.c",
                    "PRESERVED_SCOPE" to "scope.same",
                    "REVOKED_SCOPE" to "scope.same",
                ),
            ),
        )

        assertEquals("ABSTAIN", result.getString("decision_state"))
        assertEquals(0, result.getJSONArray("invalidated_state_ids").length())
        assertTrue(result.getJSONArray("preserved_state_ids").toString().contains("stable"))
    }

    private fun roles(vararg entries: Pair<String, String>): JSONObject =
        JSONObject().also { value -> entries.forEach { (key, item) -> value.put(key, item) } }

    private fun step(
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
        sessionId: String = "session-default",
        eventId: String = "event-$index",
    ): ProbeInputStep {
        val minute = index.toString().padStart(2, '0')
        val canonical = CanonicalJson.encode(
            JSONObject()
                .put("step_id", "step-$index")
                .put("event_id", eventId)
                .put("operation", operation)
                .put("session_id", sessionId)
                .put("virtual_time", "2026-01-01T00:$minute:00Z")
                .put("roles", roles),
        )
        return ProbeInputStep(
            index = index,
            stepId = "step-$index",
            eventId = eventId,
            operation = operation,
            sessionId = sessionId,
            virtualTime = "2026-01-01T00:$minute:00Z",
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
