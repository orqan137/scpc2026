package org.scpc.r2.safeshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

class SafeShareCoreTest {
    @Test
    fun duplicateActionSendEventCreatesOnlyOneEnvelopeAndDraft() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.setPurposeRoute("EXPENSE_DRAFT", "PROJECT-A")

        val first = core.receiveSharedText("share-1", "synthetic receipt")
        val duplicate = core.receiveSharedText("share-1", "synthetic receipt")
        val snapshot = core.productSnapshot()

        assertFalse(first.getBoolean("duplicate"))
        assertTrue(duplicate.getBoolean("duplicate"))
        assertEquals(first.getString("draft_id"), duplicate.getString("draft_id"))
        assertEquals(1, snapshot.getInt("share_count"))
        assertEquals(1, snapshot.getInt("active_draft_count"))
        assertEquals(0, snapshot.getInt("external_dispatch_count"))
    }

    @Test
    fun productInteractionMetricsExposeE1RouteQuestionAndE2ReuseWithoutAClaim() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-burden")

        val e1 = core.receiveSharedText("share-e1", "first synthetic memo")
        core.setPurposeRoute("goal.opaque-1", "entity.opaque-1")
        val e2 = core.receiveSharedText("share-e2", "second synthetic memo")
        val metrics = core.productSnapshot().getJSONObject("interaction_metrics")

        assertFalse(e1.getBoolean("route_reused"))
        assertEquals(1, e1.getInt("route_confirmation_required_count"))
        assertTrue(e2.getBoolean("route_reused"))
        assertEquals(0, e2.getInt("route_confirmation_required_count"))
        assertEquals(2, metrics.getInt("share_event_count"))
        assertEquals(1, metrics.getInt("route_reused_event_count"))
        assertEquals(1, metrics.getInt("route_confirmation_required_count"))
        assertEquals("share-e2", metrics.getString("last_share_event_id"))
        assertTrue(metrics.getBoolean("last_route_reused"))
        assertEquals(0, metrics.getInt("last_route_confirmation_required_count"))
    }

    @Test
    fun targetCorrectionCancelsOnlyDependentDraftAndCreatesReplacement() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.setPurposeRoute("EXPENSE_DRAFT", "PROJECT-A")
        core.receiveSharedText("share-1", "synthetic receipt")

        val pending = core.correctTarget("EXPENSE_DRAFT", "PROJECT-C")

        assertEquals("PROJECT-A", pending.getJSONObject("route_rules").getString("EXPENSE_DRAFT"))
        assertEquals("ASK", pending.getString("decision_state"))
        assertEquals(1, pending.getInt("active_draft_count"))
        assertEquals(0, pending.getInt("cancelled_draft_count"))
        assertEquals(3, pending.getInt("product_session_count"))

        val corrected = core.confirmPendingRoute()
        assertEquals("PROJECT-C", corrected.getJSONObject("route_rules").getString("EXPENSE_DRAFT"))
        assertEquals(1, corrected.getInt("active_draft_count"))
        assertEquals(1, corrected.getInt("cancelled_draft_count"))
        assertEquals("E4_RECOVERED", corrected.getString("episode"))
        assertEquals(4, corrected.getInt("product_session_count"))
        val history = corrected.getJSONArray("episode_history")
        assertNotEquals(
            history.getJSONObject(history.length() - 2).getString("session_id"),
            history.getJSONObject(history.length() - 1).getString("session_id"),
        )
        assertEquals(0, corrected.getInt("external_dispatch_count"))
    }

    @Test
    fun incomingShareDuringPendingTargetCorrectionWaitsForFinalAuthority() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-correction-admission")
        core.setPurposeRoute("EXPENSE_DRAFT", "PROJECT-A")
        core.receiveSharedText("share-before-correction", "before correction")

        core.correctTarget("EXPENSE_DRAFT", "PROJECT-C")
        val incoming = core.receiveSharedText("share-during-correction", "during correction")

        assertFalse(incoming.getBoolean("route_reused"))
        assertEquals("ASK", incoming.getString("status"))
        assertEquals(1, incoming.getInt("route_confirmation_required_count"))
        assertTrue(incoming.isNull("draft_id"))
        assertEquals(1, core.productSnapshot().getInt("active_draft_count"))

        val corrected = core.confirmPendingRoute()
        assertEquals("PROJECT-C", corrected.getJSONObject("route_rules").getString("EXPENSE_DRAFT"))
        assertEquals(2, corrected.getInt("active_draft_count"))
        assertEquals(1, corrected.getInt("cancelled_draft_count"))
        assertEquals(0, corrected.getInt("external_dispatch_count"))
    }

    @Test
    fun revokedTargetCancelsDraftWithoutExternalDispatch() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.setPurposeRoute("EXPENSE_DRAFT", "PROJECT-A")
        core.receiveSharedText("share-1", "synthetic receipt")

        val revoked = core.revokeTarget("PROJECT-A")

        assertEquals(0, revoked.getInt("active_draft_count"))
        assertEquals(1, revoked.getInt("cancelled_draft_count"))
        assertEquals(0, revoked.getInt("external_dispatch_count"))
        assertTrue(revoked.getJSONArray("revoked_scopes").toString().contains("PROJECT-A"))
    }

    @Test
    fun newProcessMarkerIncrementsDurableEpochAndKeepsShare() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        first.receiveSharedText("share-1", "synthetic receipt")

        val relaunched = SafeShareCore(store, "process-b")
        relaunched.receiveSharedText("share-2", "another synthetic receipt")
        val snapshot = relaunched.productSnapshot()

        assertEquals(1, snapshot.getInt("process_epoch"))
        assertEquals(2, snapshot.getInt("share_count"))
    }

    @Test
    fun probeUsesCurrentTargetAndExcludesDistractor() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-A")
                    .put("CURRENT_AUTHORITY", "AUTH-1"),
            ),
        )

        val decision = core.execute(
            step(
                2,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("DISTRACTOR_ENTITY", "PROJECT-B"),
            ),
        )

        assertEquals("ACT", decision.getString("decision_state"))
        assertEquals(1, decision.getJSONArray("selected_context_ids").length())
        assertFalse(decision.getJSONArray("selected_context_ids").toString().contains("PROJECT-B"))
        assertEquals("COMMITTED", decision.getJSONObject("action").getString("commit_state"))
    }

    @Test
    fun probeResetPreservesRunEvidenceAndCumulativeInteractionMetrics() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-probe-reset")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "goal.reset")
                    .put("TARGET_ENTITY", "entity.reset")
                    .put("STABLE_VALUE", "value.reset")
                    .put("CURRENT_AUTHORITY", "authority.reset"),
            ),
        )
        core.execute(
            step(
                2,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "goal.reset")
                    .put("TARGET_ENTITY", "entity.reset"),
            ),
        )

        core.execute(step(3, "RESET_AND_START", sessionId = "SESSION-2"))
        val state = store.load()
        val metrics = core.memorySnapshot().getJSONObject("probe_interaction_metrics")

        assertEquals(0, state.getJSONObject("facts").length())
        assertEquals(0, state.getJSONObject("actions").length())
        assertEquals(4, state.getJSONArray("evidence").length())
        assertEquals(4, state.getJSONArray("receipts").length())
        assertEquals(1, state.getJSONObject("decision_traces").length())
        assertEquals(1, core.decisionRequestCount())
        assertEquals(1, metrics.getInt("decision_event_count"))
        assertTrue(core.evidenceIds().contains("evidence-${SafeShareId.component("EVENT-0")}"))
        assertTrue(core.evidenceIds().contains("receipt-${SafeShareId.component("EVENT-3")}"))

        core.resetAllUserData()
        assertEquals(0, core.evidenceIds().size)
        assertEquals(0, core.memorySnapshot().getJSONObject("decision_traces").length())
        assertEquals(0, core.decisionRequestCount())
    }

    @Test
    fun correctionDeleteAndOldReplayDoNotResurrectFact() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-A")
                    .put("ONE_OFF_VALUE", "ONCE-A")
                    .put("CURRENT_AUTHORITY", "AUTH-1"),
            ),
        )
        val corrected = core.execute(
            step(
                2,
                "CORRECT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("ONE_OFF_VALUE", "ONCE-B")
                    .put("CURRENT_AUTHORITY", "AUTH-2"),
            ),
        )
        assertEquals(1, corrected.getJSONArray("invalidated_state_ids").length())

        val deleted = core.execute(
            step(
                3,
                "DELETE_FACT",
                JSONObject()
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("ONE_OFF_VALUE", "ONCE-B"),
            ),
        )
        // The observable tombstone ledger includes both the concrete fact and
        // its exact value-deletion rule; only the relation matters here.
        assertTrue(deleted.getJSONArray("tombstone_ids").length() >= 1)
        assertTrue(deleted.getJSONArray("invalidated_state_ids").length() >= 2)
        assertTrue(
            store.load().getJSONObject("facts").keys().asSequence()
                .map(store.load().getJSONObject("facts")::getJSONObject)
                .any { it.optString("value") == "ROUTE-A" && it.optString("status") == "ACTIVE" },
        )
        assertEquals(
            "SUPERSEDED",
            store.load().getJSONObject("facts").let { facts ->
                facts.keys().asSequence()
                    .map(facts::getJSONObject)
                    .single { it.optString("value") == "ONCE-A" }
                    .getString("status")
            },
        )
        assertFalse(store.load().getJSONObject("facts").toString().contains("ONCE-B"))

        val staleUpsert = core.execute(
            step(
                2,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("ONE_OFF_VALUE", "ONCE-B")
                    .put("CURRENT_AUTHORITY", "AUTH-3"),
            ),
        )
        assertEquals("ABSTAIN", staleUpsert.getString("decision_state"))

        val afterDelete = core.execute(
            step(5, "REQUEST_DECISION", JSONObject()
                .put("PRIMARY_GOAL", "GOAL-A")
                .put("TARGET_ENTITY", "PROJECT-A")),
        )
        assertEquals("ACT", afterDelete.getString("decision_state"))
        assertEquals(1, afterDelete.getJSONArray("selected_context_ids").length())
        assertTrue(afterDelete.getJSONArray("selected_context_ids").toString().contains("stable"))
    }

    @Test
    fun correctionAndValueDeletionStayInsideExactGoalAndDependencyBoundary() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-boundary")
        core.execute(step(0, "RESET_AND_START"))

        fun upsert(index: Int, goal: String, value: String) = core.execute(
            step(
                index,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", goal)
                    .put("TARGET_ENTITY", "SHARED-TARGET")
                    .put("EPHEMERAL_VALUE", value)
                    .put("CURRENT_AUTHORITY", "AUTH-$goal"),
            ),
        )

        fun correct(index: Int, goal: String, value: String) = core.execute(
            step(
                index,
                "CORRECT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", goal)
                    .put("TARGET_ENTITY", "SHARED-TARGET")
                    .put("EPHEMERAL_VALUE", value)
                    .put("CURRENT_AUTHORITY", "AUTH-$goal"),
            ),
        )

        upsert(1, "GOAL-A", "A-OLD")
        correct(2, "GOAL-A", "A-CURRENT")
        upsert(3, "GOAL-B", "B-OLD")
        correct(4, "GOAL-B", "B-CURRENT")

        val before = store.load()
        val draftsBefore = before.getJSONObject("drafts")
        assertEquals(
            2,
            draftsBefore.keys().asSequence().count {
                draftsBefore.getJSONObject(it).optString("commit_state") == "PROPOSED"
            },
        )

        val correctedA = correct(5, "GOAL-A", "A-REPLACEMENT")
        assertFalse(correctedA.getJSONArray("invalidated_state_ids").toString().contains("EVENT-4"))
        val afterCorrection = store.load()
        val factsAfterCorrection = afterCorrection.getJSONObject("facts")
        assertTrue(
            factsAfterCorrection.keys().asSequence().map(factsAfterCorrection::getJSONObject).any {
                it.optString("goal") == "GOAL-B" &&
                    it.optString("value") == "B-CURRENT" &&
                    it.optString("status") == "ACTIVE"
            },
        )
        val draftsAfterCorrection = afterCorrection.getJSONObject("drafts")
        assertEquals(
            "PROPOSED",
            draftsAfterCorrection.keys().asSequence()
                .map(draftsAfterCorrection::getJSONObject)
                .single { it.optString("purpose") == "GOAL-B" }
                .getString("commit_state"),
        )

        val deletedB = core.execute(
            step(
                6,
                "DELETE_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-B")
                    .put("TARGET_ENTITY", "SHARED-TARGET")
                    .put("EPHEMERAL_VALUE", "B-CURRENT"),
            ),
        )
        // The deleted fact invalidates its exact dependent action/draft lineage;
        // assert the relationship rather than an implementation-specific count.
        assertTrue(deletedB.getJSONArray("invalidated_state_ids").length() >= 1)
        val afterDelete = store.load()
        val factsAfterDelete = afterDelete.getJSONObject("facts")
        assertTrue(factsAfterDelete.toString().contains("B-OLD"))
        assertFalse(factsAfterDelete.toString().contains("B-CURRENT"))
        assertTrue(factsAfterDelete.toString().contains("A-REPLACEMENT"))
        val draftsAfterDelete = afterDelete.getJSONObject("drafts")
        assertEquals(
            "PROPOSED",
            draftsAfterDelete.keys().asSequence()
                .map(draftsAfterDelete::getJSONObject)
                .single {
                    it.optString("purpose") == "GOAL-A" &&
                        it.optString("source_event_id") == "EVENT-5"
                }
                .getString("commit_state"),
        )
        assertEquals(
            "CANCELLED",
            draftsAfterDelete.keys().asSequence()
                .map(draftsAfterDelete::getJSONObject)
                .single { it.optString("purpose") == "GOAL-B" }
                .getString("commit_state"),
        )
    }

    @Test
    fun distractorOnlyContextCannotAuthorizeAction() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-distractor-only")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "ADVANCE_SESSION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-D")
                    .put("TARGET_ENTITY", "DISTRACTOR-X")
                    .put("DISTRACTOR_ENTITY", "DISTRACTOR-X"),
                sessionId = "SESSION-2",
            ),
        )

        val decision = core.execute(
            step(
                2,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-D")
                    .put("TARGET_ENTITY", "DISTRACTOR-X"),
                sessionId = "SESSION-2",
            ),
        )

        assertEquals("ASK", decision.getString("decision_state"))
        assertEquals(0, decision.getJSONArray("selected_context_ids").length())
        assertTrue(decision.isNull("action"))
    }

    @Test
    fun networkStateAcceptsOnlyDeclaredControlsAndFailsClosedForInvalidValues() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-network")
        core.execute(step(0, "RESET_AND_START"))

        val delayed = core.execute(
            step(
                1,
                "SET_NETWORK",
                JSONObject()
                    .put("IGNORED_BOOLEAN", true)
                    .put("ONE_OFF_VALUE", "delayed")
                    .put("IGNORED_NUMBER", 42),
            ),
        )
        assertEquals("DELAYED", delayed.getString("network_state"))

        val explicitWins = core.execute(
            step(
                2,
                "SET_NETWORK",
                JSONObject()
                    .put("ONE_OFF_VALUE", "OFFLINE")
                    .put("NETWORK_STATE", "ONLINE"),
            ),
        )
        assertEquals("ONLINE", explicitWins.getString("network_state"))

        val scanned = core.execute(
            step(
                3,
                "SET_NETWORK",
                JSONObject()
                    .put("NETWORK_STATE", 7)
                    .put("CUSTOM_NETWORK_ROLE", "offline")
                    .put("IGNORED", false),
            ),
        )
        assertEquals("UNKNOWN", scanned.getString("network_state"))
    }

    @Test
    fun deleteWithBothTransientRolesRemovesBothExactValuesOnly() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-dual-delete")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-DUAL")
                    .put("TARGET_ENTITY", "TARGET-DUAL")
                    .put("EPHEMERAL_VALUE", "EPHEMERAL-DELETE")
                    .put("ONE_OFF_VALUE", "ONE-OFF-DELETE")
                    .put("CURRENT_AUTHORITY", "AUTH-DUAL-1"),
            ),
        )
        core.execute(
            step(
                2,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-DUAL")
                    .put("TARGET_ENTITY", "TARGET-DUAL")
                    .put("EPHEMERAL_VALUE", "EPHEMERAL-KEEP")
                    .put("ONE_OFF_VALUE", "ONE-OFF-KEEP")
                    .put("CURRENT_AUTHORITY", "AUTH-DUAL-2"),
            ),
        )

        val deleted = core.execute(
            step(
                3,
                "DELETE_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-DUAL")
                    .put("TARGET_ENTITY", "TARGET-DUAL")
                    .put("EPHEMERAL_VALUE", "EPHEMERAL-DELETE")
                    .put("ONE_OFF_VALUE", "ONE-OFF-DELETE"),
            ),
        )

        assertTrue(deleted.getJSONArray("invalidated_state_ids").length() >= 2)
        val facts = store.load().getJSONObject("facts").toString()
        assertFalse(facts.contains("EPHEMERAL-DELETE"))
        assertFalse(facts.contains("ONE-OFF-DELETE"))
        assertTrue(facts.contains("EPHEMERAL-KEEP"))
        assertTrue(facts.contains("ONE-OFF-KEEP"))
    }

    @Test
    fun delayedOutcomeRegisteredAtUpsertArrivesOnLaterEmptyAdvanceTime() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-pending-outcome-a")
        first.execute(step(0, "RESET_AND_START"))
        val upsert = first.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-LATE")
                    .put("TARGET_ENTITY", "TARGET-LATE")
                    .put("EPHEMERAL_VALUE", "VALUE-LATE")
                    .put("CURRENT_AUTHORITY", "AUTH-LATE")
                    .put("DELAYED_OUTCOME", "OUTCOME-LATE"),
            ),
        )
        val pendingId = upsert.getJSONArray("preserved_state_ids").let { ids ->
            (0 until ids.length()).map(ids::getString).single { it.startsWith("pending-outcome-") }
        }
        first.execute(
            step(
                2,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-LATE")
                    .put("TARGET_ENTITY", "TARGET-LATE"),
            ),
        )

        val relaunched = SafeShareCore(store, "process-pending-outcome-b")
        val arrived = relaunched.execute(
            step(3, "ADVANCE_TIME", JSONObject(), sessionId = "SESSION-2"),
        )
        assertTrue(arrived.getJSONArray("invalidated_state_ids").toString().contains(pendingId))
        assertEquals(0, store.load().getJSONObject("pending_delayed_outcomes").length())
        val outcomeId = "outcome-${SafeShareId.component("EVENT-3")}"
        val outcome = store.load().getJSONObject("delayed_outcomes").getJSONObject(outcomeId)
        assertEquals("OUTCOME-LATE", outcome.getString("value"))
        assertEquals("GOAL-LATE", outcome.getString("goal"))
        assertEquals("TARGET-LATE", outcome.getString("entity"))
        assertEquals("EVENT-1", outcome.getString("registered_by_event_id"))

        val changed = relaunched.execute(
            step(
                4,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-LATE")
                    .put("TARGET_ENTITY", "TARGET-LATE"),
                sessionId = "SESSION-2",
            ),
        )
        assertEquals("ASK", changed.getString("decision_state"))
        assertTrue(changed.getJSONArray("selected_context_ids").toString().contains(outcomeId))
    }

    @Test
    fun processRelaunchObservationNamesDurableFactDraftAndAction() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-preserve-a")
        first.execute(step(0, "RESET_AND_START"))
        first.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-P")
                    .put("TARGET_ENTITY", "TARGET-P")
                    .put("STABLE_VALUE", "P-OLD")
                    .put("CURRENT_AUTHORITY", "AUTH-P-OLD"),
            ),
        )
        first.execute(
            step(
                2,
                "CORRECT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-P")
                    .put("TARGET_ENTITY", "TARGET-P")
                    .put("STABLE_VALUE", "P-CURRENT")
                    .put("CURRENT_AUTHORITY", "AUTH-P"),
            ),
        )
        val durable = store.load()
        val activeFactId = durable.getJSONObject("facts").keys().asSequence().single {
            durable.getJSONObject("facts").getJSONObject(it).optString("status") == "ACTIVE"
        }
        val proposedDraftId = durable.getJSONObject("drafts").keys().asSequence().single {
            durable.getJSONObject("drafts").getJSONObject(it).optString("commit_state") == "PROPOSED"
        }
        val proposedActionId = durable.getJSONObject("actions").keys().asSequence()
            .map { durable.getJSONObject("actions").getJSONObject(it) }
            .single { it.optString("commit_state") == "PROPOSED" }
            .getString("action_id")

        val relaunched = SafeShareCore(store, "process-preserve-b").execute(
            step(3, "PROCESS_KILL_RELAUNCH", sessionId = "SESSION-2"),
        )
        val preserved = relaunched.getJSONArray("preserved_state_ids").toString()
        assertTrue(preserved.contains(activeFactId))
        assertTrue(preserved.contains(proposedDraftId))
        assertTrue(preserved.contains(proposedActionId))
        assertEquals(1, relaunched.getInt("process_epoch"))
    }

    @Test
    fun firstShareAndRouteProposalStayAskUntilExplicitConfirmation() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")

        val received = core.receiveSharedText("share-1", "synthetic receipt")
        val pending = core.proposeRoute("EXPENSE_DRAFT", "PROJECT-A")

        assertEquals("ASK", received.getString("status"))
        assertEquals("ASK", pending.getString("decision_state"))
        assertEquals(0, pending.getInt("active_draft_count"))
        assertFalse(pending.getJSONObject("route_rules").has("EXPENSE_DRAFT"))

        val confirmed = core.confirmPendingRoute()

        assertEquals("PROJECT-A", confirmed.getJSONObject("route_rules").getString("EXPENSE_DRAFT"))
        assertEquals(1, confirmed.getInt("active_draft_count"))
        assertEquals("E1_LEARNED", confirmed.getString("episode"))
    }

    @Test
    fun confirmedCorrectionCancelsOnlySamePurposeAndOldTarget() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.setPurposeRoute("PURPOSE-A", "PROJECT-A")
        core.receiveSharedText("share-a", "A")
        core.setPurposeRoute("PURPOSE-B", "PROJECT-A")
        core.receiveSharedText("share-b", "B")

        core.correctTarget("PURPOSE-A", "PROJECT-C")
        val corrected = core.confirmPendingRoute()

        assertEquals("PROJECT-C", corrected.getJSONObject("route_rules").getString("PURPOSE-A"))
        assertEquals("PROJECT-A", corrected.getJSONObject("route_rules").getString("PURPOSE-B"))
        assertEquals(2, corrected.getInt("active_draft_count"))
        assertEquals(1, corrected.getInt("cancelled_draft_count"))
    }

    @Test
    fun correctionWithExplicitGoalPreservesGoalScopedFacts() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-A")
                    .put("ONE_OFF_VALUE", "ONCE-A")
                    .put("CURRENT_AUTHORITY", "AUTH-A"),
            ),
        )

        core.execute(
            step(
                2,
                "CORRECT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("ONE_OFF_VALUE", "ONCE-B")
                    .put("CURRENT_AUTHORITY", "AUTH-B"),
            ),
        )
        val decision = core.execute(
            step(
                3,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A"),
            ),
        )

        assertEquals("ACT", decision.getString("decision_state"))
        assertEquals(2, decision.getJSONArray("selected_context_ids").length())
    }

    @Test
    fun oneOffExpiresAtSessionAdvanceWhileStableFactSurvives() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-A")
                    .put("ONE_OFF_VALUE", "ONCE-A")
                    .put("CURRENT_AUTHORITY", "AUTH-A"),
                sessionId = "SESSION-A",
            ),
        )

        val advanced = core.execute(
            step(
                2,
                "ADVANCE_SESSION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A"),
                sessionId = "SESSION-B",
            ),
        )

        assertTrue(advanced.getJSONArray("invalidated_state_ids").toString().contains("one_off"))
        assertTrue(advanced.getJSONArray("preserved_state_ids").toString().contains("stable"))
        assertFalse(advanced.getJSONArray("preserved_state_ids").toString().contains("one_off"))
    }

    @Test
    fun deleteTargetsEphemeralOrOneOffButNeverStableFact() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-A")
                    .put("ONE_OFF_VALUE", "ONCE-A")
                    .put("EPHEMERAL_VALUE", "RAW-A")
                    .put("CURRENT_AUTHORITY", "AUTH-A"),
            ),
        )

        val deleted = core.execute(
            step(
                2,
                "DELETE_FACT",
                JSONObject()
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("EPHEMERAL_VALUE", "RAW-A"),
            ),
        )
        val decision = core.execute(
            step(3, "REQUEST_DECISION", JSONObject()
                .put("PRIMARY_GOAL", "GOAL-A")
                .put("TARGET_ENTITY", "PROJECT-A")),
        )

        assertEquals(1, deleted.getJSONArray("invalidated_state_ids").length())
        assertTrue(deleted.getJSONArray("invalidated_state_ids").toString().contains("ephemeral"))
        assertTrue(deleted.getJSONArray("preserved_state_ids").toString().contains("stable"))
        assertTrue(deleted.getJSONArray("preserved_state_ids").toString().contains("one_off"))
        assertEquals("ACT", decision.getString("decision_state"))
    }

    @Test
    fun processRestartConsultsProbeActionLedgerForCommittedAndProposedWork() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-A")
                    .put("CURRENT_AUTHORITY", "AUTH-A"),
            ),
        )
        core.execute(
            step(
                2,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A"),
            ),
        )
        val committed = core.execute(step(3, "PROCESS_KILL_RELAUNCH"))

        core.execute(
            step(
                4,
                "CORRECT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-A")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("STABLE_VALUE", "ROUTE-B")
                    .put("CURRENT_AUTHORITY", "AUTH-B"),
            ),
        )
        val proposed = core.execute(step(5, "PROCESS_KILL_RELAUNCH"))

        assertEquals("CONFIRMED_COMPLETE", committed.getString("decision_state"))
        assertEquals("PENDING", proposed.getString("decision_state"))
    }

    @Test
    fun offlineDecisionWaitsWithoutCreatingAction() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(1, "SET_NETWORK", JSONObject().put("NETWORK_STATE", "OFFLINE")),
        )
        val decision = core.execute(step(2, "REQUEST_DECISION"))

        assertEquals("WAIT", decision.getString("decision_state"))
        assertTrue(decision.isNull("action"))
    }

    @Test
    fun replayReturnsOriginalIdempotencyRecord() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(1, "UPSERT_FACT", JSONObject()
                .put("PRIMARY_GOAL", "GOAL-A")
                .put("TARGET_ENTITY", "PROJECT-A")
                .put("STABLE_VALUE", "ROUTE-A")
                .put("CURRENT_AUTHORITY", "AUTH-A")),
        )
        val original = core.execute(
            step(2, "REQUEST_DECISION", JSONObject()
                .put("PRIMARY_GOAL", "GOAL-A")
                .put("TARGET_ENTITY", "PROJECT-A")),
        ).getJSONObject("action")
        val replay = core.execute(
            step(
                3,
                "REPLAY_EVENT",
                JSONObject().put("REPLAY_OF_EVENT_ID", "EVENT-2"),
            ),
        )

        assertEquals("CONFIRMED_COMPLETE", replay.getString("decision_state"))
        assertEquals(
            original.getString("idempotency_key"),
            replay.getJSONObject("action").getString("idempotency_key"),
        )
        assertEquals(
            original.getString("outcome_id"),
            replay.getJSONObject("action").getString("outcome_id"),
        )
    }

    @Test
    fun delayedOutcomeChangesNextDecisionAndKeepsCausalEvidenceAcrossRelaunch() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        first.execute(step(0, "RESET_AND_START"))
        first.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "goal.opaque-17")
                    .put("TARGET_ENTITY", "entity.opaque-41")
                    .put("STABLE_VALUE", "route.opaque-3")
                    .put("CURRENT_AUTHORITY", "authority.opaque-1"),
                sessionId = "SESSION-1",
            ),
        )
        val before = first.execute(
            step(
                2,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "goal.opaque-17")
                    .put("TARGET_ENTITY", "entity.opaque-41"),
                sessionId = "SESSION-2",
            ),
        )
        first.execute(
            step(
                3,
                "ADVANCE_TIME",
                JSONObject().put("DELAYED_OUTCOME", "outcome.opaque-93"),
                sessionId = "SESSION-2",
            ),
        )
        val relaunched = SafeShareCore(store, "process-b")
        val requestAfterOutcome = step(
            4,
            "REQUEST_DECISION",
            JSONObject()
                .put("PRIMARY_GOAL", "goal.opaque-17")
                .put("TARGET_ENTITY", "entity.opaque-41"),
            sessionId = "SESSION-3",
        )
        val after = relaunched.execute(requestAfterOutcome)
        val duplicate = relaunched.execute(requestAfterOutcome)
        val outcomeId = "outcome-${SafeShareId.component("EVENT-3")}"
        val outcomeEvidenceId = "evidence-${SafeShareId.component("EVENT-3")}"
        val requestEvidenceId = "evidence-${SafeShareId.component("EVENT-4")}"

        assertEquals("ACT", before.getString("decision_state"))
        assertEquals("ASK", after.getString("decision_state"))
        assertEquals("ASK", duplicate.getString("decision_state"))
        assertTrue(after.isNull("action"))
        assertEquals(1, before.getJSONArray("selected_context_ids").length())
        assertEquals(2, after.getJSONArray("selected_context_ids").length())
        assertTrue(after.getJSONArray("selected_context_ids").toString().contains(outcomeId))
        assertEquals(1, after.getJSONArray("auto_check_evidence_ids").length())
        assertEquals(
            requestEvidenceId,
            after.getJSONArray("auto_check_evidence_ids").getString(0),
        )

        val memory = relaunched.memorySnapshot()
        val outcome = store.load().getJSONObject("delayed_outcomes")
            .getJSONObject(outcomeId)
        val trace = memory.getJSONObject("decision_traces").getJSONObject("EVENT-4")
        val metrics = memory.getJSONObject("probe_interaction_metrics")
        assertEquals("REVIEW_REQUIRED", outcome.getString("review_state"))
        assertEquals(outcomeEvidenceId, outcome.getString("source_evidence_id"))
        assertEquals("ASK", trace.getString("decision_state"))
        assertTrue(trace.getJSONArray("cause_outcome_ids").toString().contains(outcomeId))
        assertTrue(trace.getJSONArray("cause_evidence_ids").toString().contains(outcomeEvidenceId))
        assertEquals(2, metrics.getInt("decision_event_count"))
        assertEquals(1, metrics.getInt("required_confirmation_count_total"))
        assertEquals(2, relaunched.decisionRequestCount())

        val confirmed = relaunched.execute(
            step(
                5,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "goal.opaque-17")
                    .put("TARGET_ENTITY", "entity.opaque-41")
                    .put("CURRENT_AUTHORITY", "authority.opaque-8"),
                sessionId = "SESSION-3",
            ),
        )
        assertEquals("ACT", confirmed.getString("decision_state"))
        assertEquals("COMMITTED", confirmed.getJSONObject("action").getString("commit_state"))
        assertEquals(
            "ACKNOWLEDGED",
            store.load().getJSONObject("delayed_outcomes")
                .getJSONObject(outcomeId).getString("review_state"),
        )
    }

    @Test
    fun unscopedDelayedOutcomeCannotBeClaimedWithoutAnAuthoritativeFact() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-order")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "ADVANCE_TIME",
                JSONObject().put("DELAYED_OUTCOME", "결과/opaque#71"),
            ),
        )
        core.execute(
            step(
                2,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "목표-β/17")
                    .put("TARGET_ENTITY", "방/opaque#9")
                    .put("STABLE_VALUE", "경로/opaque#4"),
            ),
        )

        val changed = core.execute(
            step(
                3,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "목표-β/17")
                    .put("TARGET_ENTITY", "방/opaque#9"),
            ),
        )
        assertEquals("ASK", changed.getString("decision_state"))
        assertEquals(0, changed.getJSONArray("selected_context_ids").length())

        val confirmed = core.execute(
            step(
                4,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", "목표-β/17")
                    .put("TARGET_ENTITY", "방/opaque#9")
                    .put("CURRENT_AUTHORITY", "권한/opaque#3"),
            ),
        )
        assertEquals("ASK", confirmed.getString("decision_state"))
    }

    @Test
    fun practiceAOpaqueVariantsRevokeOnlyScopedPartAndCommitSameProposalAfterKill() {
        val variants = listOf(
            listOf("g-17", "n-41", "n-99", "v-a", "v-b", "a-1", "a-2", "s-3", "s-8"),
            listOf("q.62", "x.14", "x.77", "m.5", "m.9", "u.2", "u.6", "c.4", "c.9"),
        )

        variants.forEachIndexed { caseIndex, value ->
            val goal = value[0]
            val target = value[1]
            val distractor = value[2]
            val oldValue = value[3]
            val newValue = value[4]
            val oldAuthority = value[5]
            val newAuthority = value[6]
            val preservedScope = value[7]
            val revokedScope = value[8]
            val store = InMemoryRelayStateStore()
            val first = SafeShareCore(store, "process-a-$caseIndex")
            first.execute(step(0, "RESET_AND_START", eventId = "reset-$caseIndex"))
            first.execute(
                step(
                    1,
                    "UPSERT_FACT",
                    JSONObject()
                        .put("PRIMARY_GOAL", goal)
                        .put("TARGET_ENTITY", target)
                        .put("STABLE_VALUE", oldValue)
                        .put("ONE_OFF_VALUE", "once-$caseIndex")
                        .put("CURRENT_AUTHORITY", oldAuthority)
                        .put("PRESERVED_SCOPE", preservedScope),
                    sessionId = "session-1-$caseIndex",
                    eventId = "upsert-$caseIndex",
                ),
            )
            first.execute(
                step(
                    2,
                    "ADVANCE_SESSION",
                    JSONObject()
                        .put("PRIMARY_GOAL", goal)
                        .put("TARGET_ENTITY", target)
                        .put("DISTRACTOR_ENTITY", distractor),
                    sessionId = "session-2-$caseIndex",
                    eventId = "advance-$caseIndex",
                ),
            )
            assertTrue(store.load().getJSONObject("facts").toString().contains(distractor))

            val beforeCorrection = first.execute(
                step(
                    3,
                    "REQUEST_DECISION",
                    JSONObject()
                        .put("PRIMARY_GOAL", goal)
                        .put("TARGET_ENTITY", target),
                    sessionId = "session-2-$caseIndex",
                    eventId = "decision-before-$caseIndex",
                ),
            )
            assertEquals("ACT", beforeCorrection.getString("decision_state"))
            assertFalse(
                beforeCorrection.getJSONArray("selected_context_ids").toString()
                    .contains(distractor),
            )

            val corrected = first.execute(
                step(
                    4,
                    "CORRECT_FACT",
                    JSONObject()
                        .put("PRIMARY_GOAL", goal)
                        .put("TARGET_ENTITY", target)
                        .put("STABLE_VALUE", newValue)
                        .put("CURRENT_AUTHORITY", newAuthority)
                        .put("PRESERVED_SCOPE", preservedScope),
                    sessionId = "session-3-$caseIndex",
                    eventId = "correct-$caseIndex",
                ),
            )
            assertEquals(1, corrected.getJSONArray("invalidated_state_ids").length())
            assertEquals(1, corrected.getJSONArray("preserved_state_ids").length())
            val currentFactId = corrected.getJSONArray("preserved_state_ids").getString(0)
            assertEquals(
                newAuthority,
                store.load().getJSONObject("facts").getJSONObject(currentFactId)
                    .getString("authority"),
            )
            val proposed = corrected.getJSONObject("action")
            assertEquals("PROPOSED", proposed.getString("commit_state"))

            val revoked = first.execute(
                step(
                    5,
                    "REVOKE_SCOPE",
                    JSONObject()
                        .put("PRESERVED_SCOPE", preservedScope)
                        .put("REVOKED_SCOPE", revokedScope),
                    sessionId = "session-3-$caseIndex",
                    eventId = "revoke-$caseIndex",
                ),
            )
            assertEquals(1, revoked.getJSONArray("invalidated_state_ids").length())
            assertTrue(
                revoked.getJSONArray("invalidated_state_ids").getString(0)
                    .contains("draft-part"),
            )
            assertTrue(revoked.getJSONArray("preserved_state_ids").toString().contains(currentFactId))
            assertTrue(revoked.getJSONArray("preserved_state_ids").toString().contains("-route"))

            val relaunched = SafeShareCore(store, "process-b-$caseIndex")
            val killed = relaunched.execute(
                step(
                    6,
                    "PROCESS_KILL_RELAUNCH",
                    sessionId = "session-4-$caseIndex",
                    eventId = "kill-$caseIndex",
                ),
            )
            assertEquals(1, killed.getInt("process_epoch"))
            assertEquals("PENDING", killed.getString("decision_state"))

            val recoveredStep = step(
                7,
                "REQUEST_DECISION",
                JSONObject()
                    .put("PRIMARY_GOAL", goal)
                    .put("TARGET_ENTITY", target),
                sessionId = "session-4-$caseIndex",
                eventId = "decision-after-$caseIndex",
            )
            val recovered = relaunched.execute(recoveredStep)
            val committed = recovered.getJSONObject("action")
            assertEquals("COMMITTED", committed.getString("commit_state"))
            assertEquals(proposed.getString("action_id"), committed.getString("action_id"))
            assertEquals(
                proposed.getString("idempotency_key"),
                committed.getString("idempotency_key"),
            )
            assertEquals(1, recovered.getJSONArray("selected_context_ids").length())
            assertEquals(currentFactId, recovered.getJSONArray("selected_context_ids").getString(0))
            val actionCount = store.load().getJSONObject("actions").length()

            val duplicateRequest = relaunched.execute(recoveredStep).getJSONObject("action")
            assertEquals(actionCount, store.load().getJSONObject("actions").length())
            assertEquals(committed.getString("action_id"), duplicateRequest.getString("action_id"))
            assertEquals(committed.getString("outcome_id"), duplicateRequest.getString("outcome_id"))
        }
    }

    @Test
    fun ambiguousRevocableDraftPartsAskWithoutInvalidation() {
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        core.setPurposeRoute("g-opaque", "n-opaque")
        core.receiveSharedText("share-1", "first")
        core.receiveSharedText("share-2", "second")

        val result = core.execute(
            step(
                1,
                "REVOKE_SCOPE",
                JSONObject()
                    .put("PRESERVED_SCOPE", "LOCAL_ROUTE")
                    .put("REVOKED_SCOPE", "scope-opaque"),
            ),
        )

        assertEquals("ASK", result.getString("decision_state"))
        assertEquals(0, result.getJSONArray("invalidated_state_ids").length())
    }

    @Test
    fun productHistoryShowsFourEpisodesAcrossAtLeastThreeSessionsIncludingRelaunch() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-a")
        first.receiveSharedText("share-1", "first")
        first.proposeRoute("g-opaque", "n-1")
        first.confirmPendingRoute()
        first.receiveSharedText("share-2", "second")
        first.correctTarget("g-opaque", "n-2")

        val relaunched = SafeShareCore(store, "process-b")
        relaunched.productSnapshot()
        val recovered = relaunched.confirmPendingRoute()
        val history = recovered.getJSONArray("episode_history")
        val episodes = (0 until history.length())
            .map { history.getJSONObject(it).getString("episode") }
        val sessions = (0 until history.length())
            .map { history.getJSONObject(it).getString("session_id") }
            .distinct()

        assertTrue(episodes.any { it.startsWith("E1_") })
        assertTrue(episodes.contains("E2_REUSE"))
        assertTrue(episodes.contains("E3_EXCEPTION_ASK"))
        assertTrue(episodes.any { it.startsWith("E4_") })
        assertEquals(
            listOf(
                "product-session-1",
                "product-session-2",
                "product-session-3",
                "product-session-4",
            ),
            sessions,
        )
        assertEquals(4, recovered.getInt("product_session_count"))
        assertEquals(1, recovered.getInt("process_epoch"))
        assertEquals("E4_RECOVERED", recovered.getString("episode"))
    }

    @Test
    fun productShareRouteAndDraftUseTheProbeFactsActionsDependencyLedger() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-parity")
        core.receiveSharedText("share-parity", "private synthetic memo")
        core.proposeRoute("EXPENSE_DRAFT", "PROJECT-A")
        core.confirmPendingRoute()

        val state = store.load()
        val envelope = state.getJSONObject("share_envelopes")
            .getJSONObject("share-${SafeShareId.component("share-parity")}")
        val rawFactId = envelope.getString("raw_fact_id")
        val routeFactId = envelope.getString("route_fact_id")
        val actionEventId = envelope.getString("action_event_id")
        val draftId = envelope.getString("draft_id")
        val facts = state.getJSONObject("facts")
        val actions = state.getJSONObject("actions")
        val dependencies = state.getJSONObject("action_dependencies")
            .getJSONArray(actionEventId)

        assertEquals("EPHEMERAL", facts.getJSONObject(rawFactId).getString("kind"))
        assertEquals("private synthetic memo", facts.getJSONObject(rawFactId).getString("value"))
        assertEquals(
            "USER_CONFIRMED_ROUTE",
            facts.getJSONObject(routeFactId).getString("authority"),
        )
        assertEquals(draftId, actions.getJSONObject(actionEventId).getString("draft_id"))
        assertEquals("PROPOSED", actions.getJSONObject(actionEventId).getString("commit_state"))
        assertTrue(dependencies.toString().contains(rawFactId))
        assertTrue(dependencies.toString().contains(routeFactId))
    }

    @Test
    fun probeDeleteScrubsLinkedShareAndReplayCannotResurrectAfterRestart() {
        val store = InMemoryRelayStateStore()
        val first = SafeShareCore(store, "process-delete-a")
        first.setPurposeRoute("EXPENSE_DRAFT", "PROJECT-A")
        first.receiveSharedText("share-delete", "secret synthetic memo")

        val deleted = first.execute(
            step(
                1,
                "DELETE_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "EXPENSE_DRAFT")
                    .put("TARGET_ENTITY", "PROJECT-A")
                    .put("EPHEMERAL_VALUE", "secret synthetic memo"),
            ),
        )
        val stateAfterDelete = store.load()

        assertTrue(deleted.getJSONArray("invalidated_state_ids").toString().contains("share-delete"))
        assertEquals(0, stateAfterDelete.getJSONObject("share_envelopes").length())
        assertFalse(CanonicalJson.encode(stateAfterDelete).contains("secret synthetic memo"))
        assertTrue(
            stateAfterDelete.getJSONObject("actions").keys().asSequence().all { eventId ->
                stateAfterDelete.getJSONObject("actions").getJSONObject(eventId)
                    .optString("commit_state") == "CANCELLED"
            },
        )

        val relaunched = SafeShareCore(store, "process-delete-b")
        val replay = relaunched.receiveSharedText("share-delete", "secret synthetic memo")

        assertEquals("DELETED", replay.getString("status"))
        assertEquals(0, relaunched.memorySnapshot().getJSONArray("shares").length())
        assertFalse(CanonicalJson.encode(store.load()).contains("secret synthetic memo"))
    }

    @Test
    fun userCanDeleteLatestShareByDefaultAndResetTheEntireSharedLedger() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-reset")
        core.setPurposeRoute("EXPENSE_DRAFT", "PROJECT-A")
        core.receiveSharedText("share-one", "first memo")
        core.receiveSharedText("share-two", "second memo")

        val afterDelete = core.deleteShareMemory()
        assertTrue(afterDelete.getBoolean("deleted"))
        assertEquals(1, afterDelete.getJSONArray("shares").length())
        assertEquals(
            "share-${SafeShareId.component("share-one")}",
            afterDelete.getJSONArray("shares").getJSONObject(0).getString("share_id"),
        )

        val reset = core.resetAllUserData()
        val state = store.load()
        assertTrue(reset.getBoolean("reset_completed"))
        assertEquals(0, reset.getJSONArray("shares").length())
        assertEquals(0, state.getJSONObject("facts").length())
        assertEquals(0, state.getJSONObject("actions").length())
        assertEquals(0, state.getJSONObject("action_dependencies").length())
        assertEquals(0, state.getJSONObject("drafts").length())
        assertEquals(0, state.getJSONArray("tombstones").length())
        assertEquals(0, state.getJSONArray("tombstoned_share_event_ids").length())

        val acceptedAfterExplicitReset = core.receiveSharedText("share-two", "second memo")
        assertEquals("ASK", acceptedAfterExplicitReset.getString("status"))
        assertEquals(1, core.productSnapshot().getInt("share_count"))
    }

    @Test
    fun advanceSessionExpiresOneOffAndCancelsOnlyItsDescendantProposalLineage() {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, "process-session-descendants")
        core.execute(step(0, "RESET_AND_START"))
        core.execute(
            step(
                1,
                "UPSERT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-SESSION")
                    .put("TARGET_ENTITY", "TARGET-SESSION")
                    .put("STABLE_VALUE", "STABLE-KEEP")
                    .put("CURRENT_AUTHORITY", "AUTH-STABLE"),
                sessionId = "SESSION-ONE",
            ),
        )
        core.execute(
            step(
                2,
                "CORRECT_FACT",
                JSONObject()
                    .put("PRIMARY_GOAL", "GOAL-SESSION")
                    .put("TARGET_ENTITY", "TARGET-SESSION")
                    .put("ONE_OFF_VALUE", "ONE-OFF-EXPIRE")
                    .put("CURRENT_AUTHORITY", "AUTH-ONE-OFF"),
                sessionId = "SESSION-ONE",
            ),
        )
        val before = store.load()
        val oneOffId = before.getJSONObject("facts").keys().asSequence().single { id ->
            before.getJSONObject("facts").getJSONObject(id).optString("value") == "ONE-OFF-EXPIRE"
        }
        val proposal = before.getJSONObject("actions").getJSONObject("EVENT-2")
        val draftId = proposal.getString("draft_id")
        val actionId = proposal.getString("action_id")

        val advanced = core.execute(
            step(3, "ADVANCE_SESSION", sessionId = "SESSION-TWO"),
        )

        val invalidated = advanced.getJSONArray("invalidated_state_ids").toString()
        assertTrue(invalidated.contains(oneOffId))
        assertTrue(invalidated.contains(actionId))
        assertTrue(invalidated.contains(draftId))
        val after = store.load()
        assertEquals("EXPIRED", after.getJSONObject("facts").getJSONObject(oneOffId).getString("status"))
        assertEquals("CANCELLED", after.getJSONObject("actions").getJSONObject("EVENT-2").getString("commit_state"))
        assertEquals("CANCELLED", after.getJSONObject("drafts").getJSONObject(draftId).getString("commit_state"))
        assertTrue(
            after.getJSONObject("facts").keys().asSequence().map(after.getJSONObject("facts")::getJSONObject)
                .any { it.optString("value") == "STABLE-KEEP" && it.optString("status") == "ACTIVE" },
        )
    }

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
