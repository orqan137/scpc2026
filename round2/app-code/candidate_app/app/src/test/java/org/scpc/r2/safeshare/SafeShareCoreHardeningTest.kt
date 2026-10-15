package org.scpc.r2.safeshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

/** Boundary regressions for the protected probe contract. */
class SafeShareCoreHardeningTest {
    @Test
    fun missingOrEmptyRolesNeverCreateAnAction() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-empty-roles")
        core.execute(step(0, "RESET_AND_START"))

        val emptyUpsert = core.execute(step(1, "UPSERT_FACT"))
        val missingDecision = core.execute(step(2, "REQUEST_DECISION"))

        assertEquals("ABSTAIN", emptyUpsert.getString("decision_state"))
        assertEquals("ASK", missingDecision.getString("decision_state"))
        assertTrue(emptyUpsert.isNull("action"))
        assertTrue(missingDecision.isNull("action"))
    }

    @Test
    fun unknownNetworkWaitsEvenWithAnOtherwiseActionableFact() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-unknown-network")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(1, "UPSERT_FACT", roles(
                "PRIMARY_GOAL" to "GOAL-NET",
                "TARGET_ENTITY" to "TARGET-NET",
                "STABLE_VALUE" to "VALUE-NET",
                "CURRENT_AUTHORITY" to "AUTH-NET",
            )),
        )
        val network = core.execute(step(2, "SET_NETWORK", roles("NETWORK_STATE" to "not-a-state")))
        val decision = core.execute(
            step(3, "REQUEST_DECISION", roles(
                "PRIMARY_GOAL" to "GOAL-NET",
                "TARGET_ENTITY" to "TARGET-NET",
            )),
        )

        assertEquals("UNKNOWN", network.getString("network_state"))
        assertEquals("WAIT", decision.getString("decision_state"))
        assertTrue(decision.isNull("action"))
    }

    @Test
    fun invalidExplicitOrUnrelatedNetworkRolesFailClosedToUnknown() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-network-roles")
        core.execute(step(0, "RESET_AND_START"))

        val invalidExplicit = core.execute(
            step(1, "SET_NETWORK", JSONObject()
                .put("NETWORK_STATE", "invalid-state")
                .put("ONE_OFF_VALUE", "ONLINE")),
        )
        val nullExplicit = core.execute(
            step(2, "SET_NETWORK", JSONObject()
                .put("NETWORK_STATE", JSONObject.NULL)
                .put("ONE_OFF_VALUE", "ONLINE")),
        )
        val unrelatedControlLikeValue = core.execute(
            step(3, "SET_NETWORK", JSONObject().put("PRIMARY_GOAL", "ONLINE")),
        )

        assertEquals("UNKNOWN", invalidExplicit.getString("network_state"))
        assertEquals("UNKNOWN", nullExplicit.getString("network_state"))
        assertEquals("UNKNOWN", unrelatedControlLikeValue.getString("network_state"))
    }

    @Test
    fun spontaneousAdvanceTimeNeedsCurrentExactDecisionLineage() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-advance-lineage")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-TIME",
            "TARGET_ENTITY" to "TARGET-TIME",
            "STABLE_VALUE" to "FACT-TIME",
            "CURRENT_AUTHORITY" to "AUTH-TIME",
        )))

        val beforeDecision = core.execute(step(2, "ADVANCE_TIME", roles(
            "PRIMARY_GOAL" to "GOAL-TIME",
            "TARGET_ENTITY" to "TARGET-TIME",
            "DELAYED_OUTCOME" to "LATE-TIME",
        )))
        core.execute(step(3, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-TIME",
            "TARGET_ENTITY" to "TARGET-TIME",
        )))
        val correlated = core.execute(step(4, "ADVANCE_TIME", roles(
            "PRIMARY_GOAL" to "GOAL-TIME",
            "TARGET_ENTITY" to "TARGET-TIME",
            "DELAYED_OUTCOME" to "LATE-TIME",
        )))
        core.execute(step(5, "RESET_AND_START", eventId = "RESET-TIME"))
        val afterReset = core.execute(step(6, "ADVANCE_TIME", roles(
            "PRIMARY_GOAL" to "GOAL-TIME",
            "TARGET_ENTITY" to "TARGET-TIME",
            "DELAYED_OUTCOME" to "LATE-TIME",
        )))

        assertEquals("ABSTAIN", beforeDecision.getString("decision_state"))
        assertEquals("PENDING", correlated.getString("decision_state"))
        assertEquals("ABSTAIN", afterReset.getString("decision_state"))
    }

    @Test
    fun exactStableDeletionDoesNotWildcardDeleteTransientFacts() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "hardening-stable-delete")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-DELETE",
            "TARGET_ENTITY" to "TARGET-DELETE",
            "STABLE_VALUE" to "STABLE-DELETE",
            "EPHEMERAL_VALUE" to "EPHEMERAL-KEEP",
            "ONE_OFF_VALUE" to "ONE-OFF-KEEP",
            "CURRENT_AUTHORITY" to "AUTH-DELETE",
        )))

        val deleted = core.execute(step(2, "DELETE_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-DELETE",
            "TARGET_ENTITY" to "TARGET-DELETE",
            "STABLE_VALUE" to "STABLE-DELETE",
        )))
        val facts = store.load().getJSONObject("facts").toString()

        assertTrue(deleted.getJSONArray("invalidated_state_ids").length() >= 1)
        assertFalse(facts.contains("STABLE-DELETE"))
        assertTrue(facts.contains("EPHEMERAL-KEEP"))
        assertTrue(facts.contains("ONE-OFF-KEEP"))
    }

    @Test
    fun resetMakesOldCommittedActionAndReplayNonActionable() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-reset-replay")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-RESET",
            "TARGET_ENTITY" to "TARGET-RESET",
            "STABLE_VALUE" to "VALUE-RESET",
            "CURRENT_AUTHORITY" to "AUTH-RESET",
        )))
        val committed = core.execute(step(2, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-RESET",
            "TARGET_ENTITY" to "TARGET-RESET",
        )))
        assertEquals("ACT", committed.getString("decision_state"))

        core.execute(step(3, "RESET_AND_START", eventId = "RESET-AGAIN"))
        val replay = core.execute(step(4, "REPLAY_EVENT", roles("REPLAY_OF_EVENT_ID" to "EVENT-2")))
        val fresh = core.execute(step(5, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-RESET",
            "TARGET_ENTITY" to "TARGET-RESET",
        )))

        assertEquals("ABSTAIN", replay.getString("decision_state"))
        assertTrue(replay.isNull("action"))
        assertEquals("ASK", fresh.getString("decision_state"))
        assertTrue(fresh.isNull("action"))
    }

    @Test
    fun deletionRuleBeforeUpsertIsGoalBoundAndWildcardWithinThatGoal() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-delete-before-upsert")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "DELETE_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-A",
            "TARGET_ENTITY" to "TARGET-SHARED",
        )))

        val blocked = core.execute(step(2, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-A",
            "TARGET_ENTITY" to "TARGET-SHARED",
            "EPHEMERAL_VALUE" to "ANY-VALUE",
            "CURRENT_AUTHORITY" to "AUTH-A",
        )))
        val allowedOtherGoal = core.execute(step(3, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-B",
            "TARGET_ENTITY" to "TARGET-SHARED",
            "EPHEMERAL_VALUE" to "ANY-VALUE",
            "CURRENT_AUTHORITY" to "AUTH-B",
        )))

        assertEquals("ABSTAIN", blocked.getString("decision_state"))
        assertEquals("NO_DECISION", allowedOtherGoal.getString("decision_state"))
        assertTrue(allowedOtherGoal.getJSONArray("preserved_state_ids").length() == 1)
    }

    @Test
    fun revokedScopeBeforeFactStaysInsideGoalTargetScopeNamespace() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-scope-namespace")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "REVOKE_SCOPE", roles(
            "PRIMARY_GOAL" to "GOAL-A",
            "TARGET_ENTITY" to "TARGET-A",
            "REVOKED_SCOPE" to "SCOPE-REVOKED",
            "PRESERVED_SCOPE" to "SCOPE-KEEP",
        )))

        val blocked = core.execute(step(2, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-A",
            "TARGET_ENTITY" to "TARGET-A",
            "STABLE_VALUE" to "BLOCKED",
            "CURRENT_AUTHORITY" to "AUTH-A",
            "PRESERVED_SCOPE" to "SCOPE-REVOKED",
        )))
        val otherScope = core.execute(step(3, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-A",
            "TARGET_ENTITY" to "TARGET-A",
            "STABLE_VALUE" to "KEPT",
            "CURRENT_AUTHORITY" to "AUTH-A",
            "PRESERVED_SCOPE" to "SCOPE-KEEP",
        )))
        val otherNamespace = core.execute(step(4, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-B",
            "TARGET_ENTITY" to "TARGET-A",
            "STABLE_VALUE" to "OTHER-GOAL",
            "CURRENT_AUTHORITY" to "AUTH-B",
            "PRESERVED_SCOPE" to "SCOPE-REVOKED",
        )))

        assertEquals("ABSTAIN", blocked.getString("decision_state"))
        assertEquals("NO_DECISION", otherScope.getString("decision_state"))
        assertEquals("NO_DECISION", otherNamespace.getString("decision_state"))
    }

    @Test
    fun ambiguousPendingDelayedOutcomesAskInsteadOfChoosingOneArbitrarily() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "hardening-ambiguous-pending")
        core.execute(step(0, "RESET_AND_START"))
        fun pending(index: Int, value: String, delayed: String) = core.execute(
            step(index, "UPSERT_FACT", roles(
                "PRIMARY_GOAL" to "GOAL-LATE",
                "TARGET_ENTITY" to "TARGET-LATE",
                "EPHEMERAL_VALUE" to value,
                "CURRENT_AUTHORITY" to "AUTH-LATE",
                "DELAYED_OUTCOME" to delayed,
            )),
        )
        pending(1, "FACT-ONE", "PENDING-ONE")
        pending(2, "FACT-TWO", "PENDING-TWO")

        val arrived = core.execute(step(3, "ADVANCE_TIME"))

        assertEquals("ASK", arrived.getString("decision_state"))
        assertEquals(0, arrived.getJSONArray("preserved_state_ids").length())
        assertEquals(2, store.load().getJSONObject("pending_delayed_outcomes").length())
    }

    @Test
    fun tombstonedPendingOutcomeCannotBeReplayedAfterItsSourceIsDeleted() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "hardening-tombstoned-pending")
        core.execute(step(0, "RESET_AND_START"))
        val registered = core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-TOMB",
            "TARGET_ENTITY" to "TARGET-TOMB",
            "EPHEMERAL_VALUE" to "FACT-TOMB",
            "CURRENT_AUTHORITY" to "AUTH-TOMB",
            "DELAYED_OUTCOME" to "PENDING-TOMB",
        )))
        val pendingId = (0 until registered.getJSONArray("preserved_state_ids").length())
            .map(registered.getJSONArray("preserved_state_ids")::getString)
            .single { it.startsWith("pending-outcome-") }
        val deleted = core.execute(step(2, "DELETE_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-TOMB",
            "TARGET_ENTITY" to "TARGET-TOMB",
            "EPHEMERAL_VALUE" to "FACT-TOMB",
        )))
        val replay = core.execute(step(3, "ADVANCE_TIME", roles(
            "PRIMARY_GOAL" to "GOAL-TOMB",
            "TARGET_ENTITY" to "TARGET-TOMB",
            "DELAYED_OUTCOME" to "PENDING-TOMB",
        )))

        assertTrue(deleted.getJSONArray("invalidated_state_ids").toString().contains(pendingId))
        assertEquals("ABSTAIN", replay.getString("decision_state"))
        assertFalse(store.load().getJSONObject("delayed_outcomes").toString().contains("PENDING-TOMB"))
    }

    @Test
    fun goalOnlyAdvanceTimeConsumesItsUniquePendingTargetNotTheLastDifferentPair() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "hardening-goal-only-time")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-ONE",
            "TARGET_ENTITY" to "TARGET-ONE",
            "STABLE_VALUE" to "FACT-ONE",
            "CURRENT_AUTHORITY" to "AUTH-ONE",
            "DELAYED_OUTCOME" to "PENDING-ONE",
        )))
        core.execute(step(2, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-ONE",
            "TARGET_ENTITY" to "TARGET-ONE",
        )))
        core.execute(step(3, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-TWO",
            "TARGET_ENTITY" to "TARGET-TWO",
            "STABLE_VALUE" to "FACT-TWO",
            "CURRENT_AUTHORITY" to "AUTH-TWO",
        )))
        core.execute(step(4, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-TWO",
            "TARGET_ENTITY" to "TARGET-TWO",
        )))

        val arrived = core.execute(step(5, "ADVANCE_TIME", roles(
            "PRIMARY_GOAL" to "GOAL-ONE",
            "DELAYED_OUTCOME" to "PENDING-ONE",
        )))
        val outcome = store.load().getJSONObject("delayed_outcomes")
            .getJSONObject("outcome-${SafeShareId.component("EVENT-5")}")

        assertEquals("PENDING", arrived.getString("decision_state"))
        assertEquals("GOAL-ONE", outcome.getString("goal"))
        assertEquals("TARGET-ONE", outcome.getString("entity"))
        assertEquals("EVENT-1", outcome.getString("registered_by_event_id"))
    }

    @Test
    fun numericAndStringGoalTargetPairsStayInSeparateSemanticNamespaces() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-typed-identifiers")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(1, "UPSERT_FACT", JSONObject()
                .put("PRIMARY_GOAL", 7)
                .put("TARGET_ENTITY", 7)
                .put("STABLE_VALUE", "NUMBER-PAIR")
                .put("CURRENT_AUTHORITY", "AUTH-NUMBER")),
        )
        core.execute(
            step(2, "UPSERT_FACT", roles(
                "PRIMARY_GOAL" to "7",
                "TARGET_ENTITY" to "7",
                "STABLE_VALUE" to "STRING-PAIR",
                "CURRENT_AUTHORITY" to "AUTH-STRING",
            )),
        )

        val numericDecision = core.execute(
            step(3, "REQUEST_DECISION", JSONObject().put("PRIMARY_GOAL", 7).put("TARGET_ENTITY", 7)),
        )
        val stringDecision = core.execute(
            step(4, "REQUEST_DECISION", roles("PRIMARY_GOAL" to "7", "TARGET_ENTITY" to "7")),
        )

        assertEquals("ACT", numericDecision.getString("decision_state"))
        assertEquals("ACT", stringDecision.getString("decision_state"))
        assertEquals(1, numericDecision.getJSONArray("selected_context_ids").length())
        assertEquals(1, stringDecision.getJSONArray("selected_context_ids").length())
        assertFalse(
            numericDecision.getJSONArray("selected_context_ids").toString() ==
                stringDecision.getJSONArray("selected_context_ids").toString(),
        )
    }

    @Test
    fun replayProtocolRejectsNonStringEventIdInsteadOfCoercingIt() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-replay-protocol")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-REPLAY",
            "TARGET_ENTITY" to "TARGET-REPLAY",
            "STABLE_VALUE" to "VALUE-REPLAY",
            "CURRENT_AUTHORITY" to "AUTH-REPLAY",
        )))
        core.execute(step(2, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-REPLAY",
            "TARGET_ENTITY" to "TARGET-REPLAY",
        )))

        val replay = core.execute(
            step(3, "REPLAY_EVENT", JSONObject().put("REPLAY_OF_EVENT_ID", 2)),
        )

        assertEquals("ABSTAIN", replay.getString("decision_state"))
        assertTrue(replay.isNull("action"))
    }

    @Test
    fun partialSelectedDependencyDeletionBlocksExplicitDelayedOutcome() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-partial-lineage")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(step(1, "UPSERT_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-PARTIAL",
            "TARGET_ENTITY" to "TARGET-PARTIAL",
            "STABLE_VALUE" to "STABLE-PARTIAL",
            "ONE_OFF_VALUE" to "ONE-OFF-PARTIAL",
            "CURRENT_AUTHORITY" to "AUTH-PARTIAL",
        )))
        val selected = core.execute(step(2, "REQUEST_DECISION", roles(
            "PRIMARY_GOAL" to "GOAL-PARTIAL",
            "TARGET_ENTITY" to "TARGET-PARTIAL",
        )))
        assertEquals(2, selected.getJSONArray("selected_context_ids").length())
        core.execute(step(3, "DELETE_FACT", roles(
            "PRIMARY_GOAL" to "GOAL-PARTIAL",
            "TARGET_ENTITY" to "TARGET-PARTIAL",
            "ONE_OFF_VALUE" to "ONE-OFF-PARTIAL",
        )))

        val delayed = core.execute(step(4, "ADVANCE_TIME", roles(
            "PRIMARY_GOAL" to "GOAL-PARTIAL",
            "TARGET_ENTITY" to "TARGET-PARTIAL",
            "DELAYED_OUTCOME" to "LATE-PARTIAL",
        )))

        assertEquals("ABSTAIN", delayed.getString("decision_state"))
        assertTrue(delayed.isNull("action"))
    }

    @Test
    fun deletedOrRevokedLastStableRouteCannotBeReusedByProductShare() {
        fun deletedRouteAsks(): JSONObject {
            val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-route-delete")
            core.setPurposeRoute("PURPOSE-DELETE", "TARGET-DELETE")
            core.execute(step(1, "DELETE_FACT", roles(
                "PRIMARY_GOAL" to "PURPOSE-DELETE",
                "TARGET_ENTITY" to "TARGET-DELETE",
                "STABLE_VALUE" to "TARGET-DELETE",
            )))
            return core.receiveSharedText("share-after-delete", "synthetic text")
        }
        fun revokedRouteAsks(): JSONObject {
            val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-route-revoke")
            core.setPurposeRoute("PURPOSE-REVOKE", "TARGET-REVOKE")
            core.execute(step(1, "REVOKE_SCOPE", roles(
                "PRIMARY_GOAL" to "PURPOSE-REVOKE",
                "TARGET_ENTITY" to "TARGET-REVOKE",
                "PRESERVED_SCOPE" to "OTHER-SCOPE",
                "REVOKED_SCOPE" to "LOCAL_ROUTE",
            )))
            return core.receiveSharedText("share-after-revoke", "synthetic text")
        }

        val afterDelete = deletedRouteAsks()
        val afterRevoke = revokedRouteAsks()

        assertEquals("ASK", afterDelete.getString("status"))
        assertFalse(afterDelete.getBoolean("route_reused"))
        assertEquals("ASK", afterRevoke.getString("status"))
        assertFalse(afterRevoke.getBoolean("route_reused"))
    }

    @Test
    fun numericBooleanRolesAndWhitespaceOpaqueIdsRemainDeterministic() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "hardening-opaque-roles")
        core.execute(step(0, "RESET_AND_START"))
        val roles = JSONObject()
            .put("PRIMARY_GOAL", "  opaque goal  ")
            .put("TARGET_ENTITY", 17)
            .put("STABLE_VALUE", true)
            .put("CURRENT_AUTHORITY", "  opaque authority  ")
        val upsert = core.execute(step(1, "UPSERT_FACT", roles))
        val decision = core.execute(
            step(2, "REQUEST_DECISION", JSONObject()
                .put("PRIMARY_GOAL", "  opaque goal  ")
                .put("TARGET_ENTITY", 17)),
        )

        assertEquals("NO_DECISION", upsert.getString("decision_state"))
        assertEquals("ACT", decision.getString("decision_state"))
        assertEquals(1, decision.getJSONArray("selected_context_ids").length())
    }

    private fun roles(vararg entries: Pair<String, String>): JSONObject =
        JSONObject().also { value -> entries.forEach { (key, item) -> value.put(key, item) } }

    private fun step(
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
        sessionId: String = "SESSION-1",
        eventId: String = "EVENT-$index",
    ): ProbeInputStep {
        val minute = index.toString().padStart(2, '0')
        val body = JSONObject()
            .put("step_id", "STEP-$index")
            .put("operation", operation)
            .put("session_id", sessionId)
            .put("virtual_time", "2026-01-01T00:$minute:00Z")
            .put("event_id", eventId)
            .put("roles", roles)
        val canonical = CanonicalJson.encode(body)
        return ProbeInputStep(
            index = index,
            stepId = "STEP-$index",
            operation = operation,
            sessionId = sessionId,
            virtualTime = "2026-01-01T00:$minute:00Z",
            eventId = eventId,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
