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
 * Test-only operation-order metamorphic coverage.  These sequences use values
 * and event IDs unrelated to the public Runner fixture, while exercising the
 * production core's restart, replay, late-result and deletion paths together.
 */
class OperationOrderMetamorphicTest {
    @Test
    fun deletionRestartReplayAndLateOutcomePreserveSafetyAcrossAllOrders() {
        val operations = listOf("KILL", "REPLAY", "DELIVER", "DELETE")
        val permutations = permutationsOf(operations)
        assertEquals(24, permutations.size)

        permutations.forEachIndexed { caseIndex, order ->
            val store = InMemoryRelayStateStore()
            var core = SafeShareCore(store, "metamorphic-$caseIndex-a")
            val observations = mutableListOf<JSONObject>()
            val goal = "GOAL-META-$caseIndex"
            val target = "TARGET-META-$caseIndex"
            val oneOff = "ONE-OFF-META-$caseIndex"
            val decisionEvent = "decision-meta-$caseIndex"

            observations += core.execute(step(caseIndex, 0, "RESET_AND_START"))
            observations += core.execute(
                step(
                    caseIndex,
                    1,
                    "UPSERT_FACT",
                    roles(
                        "PRIMARY_GOAL" to goal,
                        "TARGET_ENTITY" to target,
                        "STABLE_VALUE" to "STABLE-META-$caseIndex",
                        "ONE_OFF_VALUE" to oneOff,
                        "CURRENT_AUTHORITY" to "AUTH-META-$caseIndex",
                        "PRESERVED_SCOPE" to "SCOPE-META-$caseIndex",
                        "DELAYED_OUTCOME" to "PENDING-META-$caseIndex",
                    ),
                ),
            )
            observations += core.execute(
                step(
                    caseIndex,
                    2,
                    "REQUEST_DECISION",
                    roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
                    eventId = decisionEvent,
                ),
            )

            order.forEachIndexed { offset, operation ->
                val index = offset + 3
                val result = when (operation) {
                    "KILL" -> {
                        core = SafeShareCore(store, "metamorphic-$caseIndex-kill")
                        core.execute(step(caseIndex, index, "PROCESS_KILL_RELAUNCH"))
                    }
                    "REPLAY" -> core.execute(
                        step(
                            caseIndex,
                            index,
                            "REPLAY_EVENT",
                            roles("REPLAY_OF_EVENT_ID" to decisionEvent),
                        ),
                    )
                    "DELIVER" -> core.execute(
                        step(
                            caseIndex,
                            index,
                            "ADVANCE_TIME",
                            roles(
                                "PRIMARY_GOAL" to goal,
                                "TARGET_ENTITY" to target,
                                "DELAYED_OUTCOME" to "DELIVERED-META-$caseIndex",
                            ),
                        ),
                    )
                    "DELETE" -> core.execute(
                        step(
                            caseIndex,
                            index,
                            "DELETE_FACT",
                            roles(
                                "PRIMARY_GOAL" to goal,
                                "TARGET_ENTITY" to target,
                                "ONE_OFF_VALUE" to oneOff,
                            ),
                        ),
                    )
                    else -> error("unsupported operation: $operation")
                }
                observations += result
            }

            observations.zipWithNext().forEach { (before, after) ->
                assertEquals(
                    "case=$caseIndex order=$order",
                    before.getString("state_after_sha256"),
                    after.getString("state_before_sha256"),
                )
            }
            val state = store.load()
            val committedActions = state.getJSONObject("actions").keys().asSequence()
                .map { state.getJSONObject("actions").getJSONObject(it) }
                .count { it.optString("commit_state") == "COMMITTED" }
            assertEquals("case=$caseIndex order=$order", 1, committedActions)
            assertTrue("case=$caseIndex order=$order", state.getInt("process_epoch") >= 1)
            assertFalse(
                "case=$caseIndex order=$order",
                state.getJSONObject("facts").toString().contains(oneOff),
            )
            val outcomes = state.getJSONObject("delayed_outcomes")
            outcomes.keys().asSequence().forEach { outcomeId ->
                val outcome = outcomes.getJSONObject(outcomeId)
                assertFalse(
                    "case=$caseIndex order=$order outcome=$outcomeId",
                    outcome.optString("status") == "ACTIVE",
                )
                assertFalse(
                    "case=$caseIndex order=$order outcome=$outcomeId",
                    outcome.has("value"),
                )
            }
            assertEquals(0, state.getJSONObject("pending_delayed_outcomes").length())
        }
    }

    private fun permutationsOf(values: List<String>): List<List<String>> =
        if (values.isEmpty()) listOf(emptyList()) else values.indices.flatMap { index ->
            val head = values[index]
            val tail = values.filterIndexed { other, _ -> other != index }
            permutationsOf(tail).map { listOf(head) + it }
        }

    private fun roles(vararg values: Pair<String, String>): JSONObject =
        JSONObject().also { roles -> values.forEach { (key, value) -> roles.put(key, value) } }

    private fun step(
        caseIndex: Int,
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
        eventId: String = "meta-$caseIndex-event-$index",
    ): ProbeInputStep {
        val time = "2026-02-01T00:${index.toString().padStart(2, '0')}:00Z"
        val canonical = CanonicalJson.encode(
            JSONObject()
                .put("step_id", "meta-$caseIndex-step-$index")
                .put("event_id", eventId)
                .put("operation", operation)
                .put("session_id", "meta-$caseIndex-session")
                .put("virtual_time", time)
                .put("roles", roles),
        )
        return ProbeInputStep(
            index = index,
            stepId = "meta-$caseIndex-step-$index",
            eventId = eventId,
            operation = operation,
            sessionId = "meta-$caseIndex-session",
            virtualTime = time,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
