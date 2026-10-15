package org.scpc.r2.safeshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

/**
 * A deterministic 80-step, all-operation horizon.  It is deliberately
 * unrelated to the public fixture and checks the same production core that
 * the protected adapter calls.  Each burst replay must return the original
 * committed action rather than create a second commit.
 */
class LongHorizonReconciliationTest {
    @Test
    fun eightyStepsKeepReplayExactlyOnceAcrossAuthorityChangesAndRestarts() {
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "horizon-process-0")
        var index = 1
        val observations = mutableListOf<JSONObject>()

        fun run(operation: String, roles: JSONObject = JSONObject(), eventId: String = "horizon-event-$index"): JSONObject {
            val observation = core.execute(step(index, operation, roles, eventId))
            observations += observation
            index += 1
            return observation
        }

        run("RESET_AND_START")
        repeat(6) { cycle ->
            val goal = "goal-horizon-$cycle"
            val target = "target-horizon-$cycle"
            val scope = "scope-horizon-$cycle"
            val decisionEvent = "horizon-decision-$cycle"
            run(
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to goal,
                    "TARGET_ENTITY" to target,
                    "STABLE_VALUE" to "stable-horizon-$cycle",
                    "ONE_OFF_VALUE" to "one-off-horizon-$cycle",
                    "CURRENT_AUTHORITY" to "authority-horizon-$cycle-a",
                    "PRESERVED_SCOPE" to scope,
                    "DELAYED_OUTCOME" to "pending-horizon-$cycle",
                ),
            )
            val decision = run(
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
                decisionEvent,
            )
            assertEquals("cycle=$cycle", "ACT", decision.getString("decision_state"))
            val firstAction = decision.getJSONObject("action")
            repeat(3) { replay ->
                val replayed = run(
                    "REPLAY_EVENT",
                    roles("REPLAY_OF_EVENT_ID" to decisionEvent),
                    "horizon-replay-$cycle-$replay",
                )
                assertEquals(
                    "cycle=$cycle replay=$replay",
                    firstAction.getString("action_id"),
                    replayed.getJSONObject("action").getString("action_id"),
                )
                assertEquals(
                    "cycle=$cycle replay=$replay",
                    firstAction.getString("idempotency_key"),
                    replayed.getJSONObject("action").getString("idempotency_key"),
                )
            }
            run(
                "ADVANCE_SESSION",
                roles(
                    "PRIMARY_GOAL" to goal,
                    "TARGET_ENTITY" to target,
                    "DISTRACTOR_ENTITY" to "distractor-horizon-$cycle",
                ),
            )
            run(
                "CORRECT_FACT",
                roles(
                    "PRIMARY_GOAL" to goal,
                    "TARGET_ENTITY" to target,
                    "STABLE_VALUE" to "stable-horizon-$cycle-b",
                    "CURRENT_AUTHORITY" to "authority-horizon-$cycle-b",
                    "PRESERVED_SCOPE" to scope,
                ),
            )
            run("REQUEST_DECISION", roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target))
            core = SafeShareCore(store, "horizon-process-${cycle + 1}")
            run("PROCESS_KILL_RELAUNCH")
            run("REQUEST_DECISION", roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target))
            run("DELIVER_OUT_OF_ORDER", roles("OLDER_EVENT_ID" to decisionEvent))
            run("DELETE_FACT", roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target))
        }
        run("REVOKE_SCOPE", roles("PRIMARY_GOAL" to "goal-horizon-0", "TARGET_ENTITY" to "target-horizon-0", "REVOKED_SCOPE" to "scope-horizon-0", "PRESERVED_SCOPE" to "scope-keep"))
        run("SET_NETWORK", roles("NETWORK_STATE" to "OFFLINE"))
        run("ADVANCE_TIME", roles("PRIMARY_GOAL" to "goal-horizon-0", "TARGET_ENTITY" to "target-horizon-0", "DELAYED_OUTCOME" to "late-horizon"))
        run("SET_NETWORK", roles("NETWORK_STATE" to "ONLINE"))
        run("REQUEST_DECISION", roles("PRIMARY_GOAL" to "goal-horizon-0", "TARGET_ENTITY" to "target-horizon-0"))
        run("ADVANCE_SESSION", roles("PRIMARY_GOAL" to "goal-horizon-0", "TARGET_ENTITY" to "target-horizon-0", "DISTRACTOR_ENTITY" to "distractor-horizon-final"))
        run("EXPORT_AND_END", eventId = "horizon-export")

        assertEquals(80, observations.size)
        observations.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.getString("state_after_sha256"), next.getString("state_before_sha256"))
        }
        val receiptIds = observations.map { it.getJSONArray("receipt_ids").getString(0) }
        val evidenceIds = observations.map { it.getJSONArray("auto_check_evidence_ids").getString(0) }
        assertEquals(80, receiptIds.toSet().size)
        assertEquals(80, evidenceIds.toSet().size)
        assertEquals(0, core.productSnapshot().getInt("external_dispatch_count"))
        assertTrue(core.productSnapshot().getInt("process_epoch") >= 6)

        val actionKeys = store.load().getJSONObject("actions").keys().asSequence()
            .map { actionId -> store.load().getJSONObject("actions").getJSONObject(actionId).getString("idempotency_key") }
            .toList()
        assertEquals(actionKeys.size, actionKeys.toSet().size)
        assertFalse(store.load().toString().contains("pending-horizon-0"))
    }

    private fun roles(vararg values: Pair<String, String>): JSONObject =
        JSONObject().also { objectValue -> values.forEach { (key, value) -> objectValue.put(key, value) } }

    private fun step(
        index: Int,
        operation: String,
        roles: JSONObject,
        eventId: String,
    ): ProbeInputStep {
        val minute = index % 60
        val hour = index / 60
        val virtualTime = "2026-04-04T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:00Z"
        val canonical = CanonicalJson.encode(
            JSONObject()
                .put("step_id", "horizon-step-$index")
                .put("event_id", eventId)
                .put("operation", operation)
                .put("session_id", "horizon-session-$index")
                .put("virtual_time", virtualTime)
                .put("roles", roles),
        )
        return ProbeInputStep(
            index = index,
            stepId = "horizon-step-$index",
            eventId = eventId,
            operation = operation,
            sessionId = "horizon-session-$index",
            virtualTime = virtualTime,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
