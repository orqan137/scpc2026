package org.scpc.r2.safeshare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

/** Production-core counterexamples for distractor invariance and no resurrection. */
class AuthorityAndDistractorMetamorphicTest {
    @Test
    fun correctOpaqueTargetIsInvariantToZeroOneFiveAndTwentyDistractors() {
        listOf(0, 1, 5, 20).forEach { distractorCount ->
            val core = SafeShareCore(InMemoryRelayStateStore(), "distractor-$distractorCount")
            val goal = "GOAL-PRIMARY"
            val target = "TARGET-OPAQUE-CORRECT"
            core.execute(step(distractorCount, 0, "RESET_AND_START"))
            core.execute(
                step(
                    distractorCount,
                    1,
                    "UPSERT_FACT",
                    roles(
                        "PRIMARY_GOAL" to goal,
                        "TARGET_ENTITY" to target,
                        "STABLE_VALUE" to "STABLE-PRIMARY",
                        "CURRENT_AUTHORITY" to "AUTH-CURRENT",
                    ),
                ),
            )
            repeat(distractorCount) { index ->
                core.execute(
                    step(
                        distractorCount,
                        index + 2,
                        "ADVANCE_SESSION",
                        roles(
                            "PRIMARY_GOAL" to goal,
                            "TARGET_ENTITY" to target,
                            "DISTRACTOR_ENTITY" to "TARGET-DISTRACTOR-$index",
                            "CURRENT_AUTHORITY" to "AUTH-DISTRACTOR-$index",
                        ),
                    ),
                )
            }
            val decision = core.execute(
                step(
                    distractorCount,
                    distractorCount + 2,
                    "REQUEST_DECISION",
                    roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
                ),
            )

            assertEquals("count=$distractorCount", "ACT", decision.getString("decision_state"))
            assertEquals("count=$distractorCount", 1, decision.getJSONArray("selected_context_ids").length())
            assertFalse(
                "count=$distractorCount",
                decision.getJSONArray("selected_context_ids").toString().contains("DISTRACTOR"),
            )
        }
    }

    @Test
    fun deleteThenExportThenRelaunchCannotResurrectCorrectedAuthorityOrLateOutcome() {
        val store = InMemoryRelayStateStore()
        var core = SafeShareCore(store, "authority-delete-a")
        val goal = "GOAL-NO-RESURRECTION"
        val target = "TARGET-NO-RESURRECTION"
        core.execute(step(77, 0, "RESET_AND_START"))
        core.execute(
            step(
                77,
                1,
                "UPSERT_FACT",
                roles(
                    "PRIMARY_GOAL" to goal,
                    "TARGET_ENTITY" to target,
                    "ONE_OFF_VALUE" to "AUTHORITY-OLD",
                    "CURRENT_AUTHORITY" to "AUTH-OLD",
                    "DELAYED_OUTCOME" to "LATE-OLD",
                ),
            ),
        )
        core.execute(
            step(
                77,
                2,
                "CORRECT_FACT",
                roles(
                    "PRIMARY_GOAL" to goal,
                    "TARGET_ENTITY" to target,
                    "ONE_OFF_VALUE" to "AUTHORITY-CORRECTED",
                    "CURRENT_AUTHORITY" to "AUTH-CORRECTED",
                    "PRESERVED_SCOPE" to "SCOPE-CORRECTED",
                ),
            ),
        )
        val deleted = core.execute(
            step(
                77,
                3,
                "DELETE_FACT",
                roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
            ),
        )
        assertTrue(deleted.getJSONArray("invalidated_state_ids").length() >= 2)
        core.execute(step(77, 4, "EXPORT_AND_END"))

        core = SafeShareCore(store, "authority-delete-b")
        core.execute(step(77, 5, "PROCESS_KILL_RELAUNCH"))
        val stale = core.execute(
            step(
                77,
                6,
                "ADVANCE_TIME",
                roles(
                    "PRIMARY_GOAL" to goal,
                    "TARGET_ENTITY" to target,
                    "DELAYED_OUTCOME" to "LATE-OLD",
                ),
            ),
        )
        val decision = core.execute(
            step(
                77,
                7,
                "REQUEST_DECISION",
                roles("PRIMARY_GOAL" to goal, "TARGET_ENTITY" to target),
            ),
        )

        assertEquals("ABSTAIN", stale.getString("decision_state"))
        assertEquals("ASK", decision.getString("decision_state"))
        val persisted = store.load().toString()
        assertFalse(persisted.contains("AUTHORITY-OLD"))
        assertFalse(persisted.contains("AUTHORITY-CORRECTED"))
        assertFalse(persisted.contains("LATE-OLD"))
    }

    private fun roles(vararg values: Pair<String, String>): JSONObject =
        JSONObject().also { roles -> values.forEach { (key, value) -> roles.put(key, value) } }

    private fun step(
        caseIndex: Int,
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
    ): ProbeInputStep {
        val eventId = "authority-$caseIndex-event-$index"
        val time = "2026-03-01T00:${index.toString().padStart(2, '0')}:00Z"
        val canonical = CanonicalJson.encode(
            JSONObject()
                .put("step_id", "authority-$caseIndex-step-$index")
                .put("event_id", eventId)
                .put("operation", operation)
                .put("session_id", "authority-$caseIndex-session")
                .put("virtual_time", time)
                .put("roles", roles),
        )
        return ProbeInputStep(
            index = index,
            stepId = "authority-$caseIndex-step-$index",
            eventId = eventId,
            operation = operation,
            sessionId = "authority-$caseIndex-session",
            virtualTime = time,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }
}
