package org.scpc.r2.safeshare

import java.util.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

/**
 * Deterministic, repository-owned fuzzing of production Probe operations.
 * No public Runner IDs, token strings or hidden answers are used.  Every seed
 * is replayable from this source and checks continuity/safety invariants only.
 */
class DeterministicOperationFuzzTest {
    @Test
    fun fiveHundredOperationSequencesKeepStateEvidenceAndActionsWellFormed() {
        repeat(500) { seed -> runSequence(seed) }
    }

    private fun runSequence(seed: Int) {
        val random = Random(seed.toLong())
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "fuzz-$seed-process-0")
        val observations = mutableListOf<JSONObject>()
        val decisionEvents = mutableListOf<String>()
        observations += core.execute(step(seed, 0, "RESET_AND_START"))

        repeat(20) { offset ->
            val index = offset + 1
            val goal = "goal-fuzz-${random.nextInt(4)}"
            val target = "target-fuzz-${random.nextInt(5)}"
            val eventId = "fuzz-$seed-event-$index"
            val operation = random.nextInt(10)
            val result = when (operation) {
                0 -> core.execute(
                    step(
                        seed,
                        index,
                        "UPSERT_FACT",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            (if (random.nextBoolean()) "STABLE_VALUE" else "ONE_OFF_VALUE") to
                                "value-fuzz-$seed-$index",
                            "CURRENT_AUTHORITY" to "authority-fuzz-${random.nextInt(3)}",
                            "PRESERVED_SCOPE" to "scope-fuzz-${random.nextInt(3)}",
                            "DELAYED_OUTCOME" to "pending-fuzz-$seed-$index",
                        ),
                        eventId,
                    ),
                )
                1 -> {
                    decisionEvents += eventId
                    core.execute(
                        step(
                            seed,
                            index,
                            "REQUEST_DECISION",
                            roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
                            eventId,
                        ),
                    )
                }
                2 -> core.execute(
                    step(
                        seed,
                        index,
                        "CORRECT_FACT",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "ONE_OFF_VALUE" to "corrected-fuzz-$seed-$index",
                            "CURRENT_AUTHORITY" to "authority-corrected-${random.nextInt(3)}",
                            "PRESERVED_SCOPE" to "scope-fuzz-${random.nextInt(3)}",
                        ),
                        eventId,
                    ),
                )
                3 -> core.execute(
                    step(
                        seed,
                        index,
                        "REVOKE_SCOPE",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "PRESERVED_SCOPE" to "scope-fuzz-${random.nextInt(3)}",
                            "REVOKED_SCOPE" to "scope-fuzz-${random.nextInt(3)}",
                        ),
                        eventId,
                    ),
                )
                4 -> core.execute(
                    step(
                        seed,
                        index,
                        "DELETE_FACT",
                        roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
                        eventId,
                    ),
                )
                5 -> core.execute(
                    step(
                        seed,
                        index,
                        "ADVANCE_SESSION",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "DISTRACTOR_ENTITY" to "distractor-fuzz-${random.nextInt(12)}",
                            "CURRENT_AUTHORITY" to "authority-distractor-${random.nextInt(3)}",
                        ),
                        eventId,
                    ),
                )
                6 -> core.execute(
                    step(
                        seed,
                        index,
                        "SET_NETWORK",
                        roles("NETWORK_STATE" to if (random.nextBoolean()) "ONLINE" else "OFFLINE"),
                        eventId,
                    ),
                )
                7 -> core.execute(
                    step(
                        seed,
                        index,
                        "ADVANCE_TIME",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "DELAYED_OUTCOME" to "delivered-fuzz-$seed-$index",
                        ),
                        eventId,
                    ),
                )
                8 -> core.execute(
                    step(
                        seed,
                        index,
                        "REPLAY_EVENT",
                        roles(
                            "REPLAY_OF_EVENT_ID" to decisionEvents
                                .randomOrNull(random)
                                .orEmpty(),
                        ),
                        eventId,
                    ),
                )
                else -> {
                    core = SafeShareCore(store, "fuzz-$seed-process-${index + 1}")
                    core.execute(step(seed, index, "PROCESS_KILL_RELAUNCH", eventId = eventId))
                }
            }
            observations += result
        }

        observations.zipWithNext().forEach { (previous, next) ->
            assertEquals(
                "seed=$seed",
                previous.getString("state_after_sha256"),
                next.getString("state_before_sha256"),
            )
            assertEquals(64, next.getString("state_before_sha256").length)
            assertEquals(64, next.getString("state_after_sha256").length)
        }
        val actions = store.load().getJSONObject("actions")
        val idempotencyKeys = actions.keys().asSequence().map { actionId ->
            actions.getJSONObject(actionId).optString("idempotency_key")
        }.filter(String::isNotEmpty).toList()
        assertEquals("seed=$seed", idempotencyKeys.size, idempotencyKeys.toSet().size)
        assertEquals("seed=$seed", 0, core.productSnapshot().getInt("external_dispatch_count"))
        assertTrue("seed=$seed", store.load().getInt("process_epoch") >= 0)
    }

    private fun List<String>.randomOrNull(random: Random): String? =
        if (isEmpty()) null else get(random.nextInt(size))

    private fun roles(vararg values: Pair<String, String>): JSONObject =
        JSONObject().also { roles -> values.forEach { (key, value) -> roles.put(key, value) } }

    private fun step(
        seed: Int,
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
        eventId: String = "fuzz-$seed-event-$index",
    ): ProbeInputStep {
        val time = "2026-04-01T00:${index.toString().padStart(2, '0')}:00Z"
        val canonical = CanonicalJson.encode(
            JSONObject()
                .put("step_id", "fuzz-$seed-step-$index")
                .put("event_id", eventId)
                .put("operation", operation)
                .put("session_id", "fuzz-$seed-session")
                .put("virtual_time", time)
                .put("roles", roles),
        )
        return ProbeInputStep(
            index = index,
            stepId = "fuzz-$seed-step-$index",
            eventId = eventId,
            operation = operation,
            sessionId = "fuzz-$seed-session",
            virtualTime = time,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
