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
 * Property coverage for the protocol boundaries omitted by the ordinary
 * happy-path rehearsal: an in-run reset, stale/out-of-order delivery, and a
 * terminal export.  Inputs are repository-owned synthetic values only; no
 * Runner IDs, answers, or scoring fields are involved.
 */
class ProtocolBoundaryFuzzTest {
    @Test
    fun boundaryMixesKeepOneStepEvidenceAndAContinuousStateChain() {
        repeat(240) { seed -> runSequence(seed) }
    }

    private fun runSequence(seed: Int) {
        val random = Random(7_001L + seed)
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "boundary-$seed-process-0")
        val observations = mutableListOf<JSONObject>()
        val decisionEvents = mutableListOf<String>()

        observations += core.execute(step(seed, 0, "RESET_AND_START"))
        repeat(32) { offset ->
            val index = offset + 1
            val goal = "goal-boundary-${random.nextInt(5)}"
            val target = "target-boundary-${random.nextInt(6)}"
            val eventId = "boundary-$seed-event-$index"
            val result = when (random.nextInt(12)) {
                0 -> core.execute(step(seed, index, "RESET_AND_START", eventId = eventId))
                1 -> core.execute(
                    step(
                        seed,
                        index,
                        "UPSERT_FACT",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            (if (random.nextBoolean()) "STABLE_VALUE" else "ONE_OFF_VALUE") to
                                "value-boundary-$seed-$index",
                            "CURRENT_AUTHORITY" to "authority-${random.nextInt(4)}",
                            "PRESERVED_SCOPE" to "scope-${random.nextInt(4)}",
                            "DELAYED_OUTCOME" to "pending-boundary-$seed-$index",
                        ),
                        eventId,
                    ),
                )
                2 -> {
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
                3 -> core.execute(
                    step(
                        seed,
                        index,
                        "CORRECT_FACT",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "ONE_OFF_VALUE" to "corrected-boundary-$seed-$index",
                            "CURRENT_AUTHORITY" to "authority-corrected-${random.nextInt(4)}",
                            "PRESERVED_SCOPE" to "scope-${random.nextInt(4)}",
                        ),
                        eventId,
                    ),
                )
                4 -> core.execute(
                    step(
                        seed,
                        index,
                        "REVOKE_SCOPE",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "REVOKED_SCOPE" to "scope-${random.nextInt(4)}",
                            "PRESERVED_SCOPE" to "scope-${random.nextInt(4)}",
                        ),
                        eventId,
                    ),
                )
                5 -> core.execute(
                    step(seed, index, "DELETE_FACT", roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target), eventId),
                )
                6 -> core.execute(
                    step(
                        seed,
                        index,
                        "ADVANCE_SESSION",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "DISTRACTOR_ENTITY" to "distractor-boundary-${random.nextInt(16)}",
                            "CURRENT_AUTHORITY" to "authority-${random.nextInt(4)}",
                        ),
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
                            "DELAYED_OUTCOME" to "delivered-boundary-$seed-$index",
                        ),
                        eventId,
                    ),
                )
                8 -> core.execute(
                    step(
                        seed,
                        index,
                        "REPLAY_EVENT",
                        roles("REPLAY_OF_EVENT_ID" to decisionEvents.randomOrEmpty(random)),
                        eventId,
                    ),
                )
                9 -> core.execute(step(seed, index, "DELIVER_OUT_OF_ORDER", eventId = eventId))
                10 -> {
                    core = SafeShareCore(store, "boundary-$seed-process-$index")
                    core.execute(step(seed, index, "PROCESS_KILL_RELAUNCH", eventId = eventId))
                }
                else -> core.execute(
                    step(
                        seed,
                        index,
                        "SET_NETWORK",
                        roles("NETWORK_STATE" to if (random.nextBoolean()) "ONLINE" else "OFFLINE"),
                        eventId,
                    ),
                )
            }
            observations += result
        }
        observations += core.execute(step(seed, 33, "EXPORT_AND_END", eventId = "boundary-$seed-export"))

        observations.zipWithNext().forEach { (previous, next) ->
            assertEquals("seed=$seed", previous.getString("state_after_sha256"), next.getString("state_before_sha256"))
        }
        observations.forEachIndexed { index, observation ->
            assertEquals("seed=$seed index=$index", 64, observation.getString("state_after_sha256").length)
            assertEquals("seed=$seed index=$index", 1, observation.getJSONArray("receipt_ids").length())
            assertEquals("seed=$seed index=$index", 1, observation.getJSONArray("auto_check_evidence_ids").length())
        }
        val finalState = core.productSnapshot()
        assertEquals("seed=$seed", 0, finalState.getInt("external_dispatch_count"))
        assertTrue("seed=$seed", finalState.getInt("process_epoch") >= 0)
    }

    private fun List<String>.randomOrEmpty(random: Random): String =
        if (isEmpty()) "" else get(random.nextInt(size))

    private fun roles(vararg values: Pair<String, String>): JSONObject =
        JSONObject().also { roles -> values.forEach { (key, value) -> roles.put(key, value) } }

    private fun step(
        seed: Int,
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
        eventId: String = "boundary-$seed-event-$index",
    ): ProbeInputStep {
        val time = "2026-04-02T00:${index.toString().padStart(2, '0')}:00Z"
        val canonical = CanonicalJson.encode(
            JSONObject()
                .put("step_id", "boundary-$seed-step-$index")
                .put("event_id", eventId)
                .put("operation", operation)
                .put("session_id", "boundary-$seed-session")
                .put("virtual_time", time)
                .put("roles", roles),
        )
        return ProbeInputStep(
            index = index,
            stepId = "boundary-$seed-step-$index",
            eventId = eventId,
            operation = operation,
            sessionId = "boundary-$seed-session",
            virtualTime = time,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
