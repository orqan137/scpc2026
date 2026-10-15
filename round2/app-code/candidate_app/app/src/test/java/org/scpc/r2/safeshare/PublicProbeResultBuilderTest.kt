package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputParser
import org.scpc.r2.probe.Sha256

class PublicProbeResultBuilderTest {
    @Test
    fun resultUsesRawInputBytesAndMatchesPublicSchemaShape() {
        val raw = """
            {
              "schema_version": "3.0.0-draft",
              "content_class": "PUBLIC_REHEARSAL_NOT_OFFICIAL",
              "probe_contract_profile": "DACON_SIGNED_RUNNER_PROTECTED_COMPONENT_V3_DRAFT",
              "probe_pack_id": "PUBLIC-TEST-MINIMAL-01",
              "instance_id": "PUBLIC-TEST-MINIMAL-01-INSTANCE-A",
              "request_nonce": "PUBLIC_NONCE_0001",
              "operator_run_token": "PUBLIC_REFERENCE_01",
              "candidate_id": "TEST_ONLY",
              "mission_id": "safe-share-test",
              "release_attestation_id": "ra-v1:2036053b4cdfe780d15be633acd3ccac8d1c4890728747aa2a9f60d45edddf0a",
              "surface_nonce": "PUBLIC_SURFACE_A",
              "steps": [
                {
                  "step_id": "PUBLIC-01",
                  "operation": "RESET_AND_START",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:00:00Z",
                  "event_id": "EVENT-PUBLIC-RESET-01",
                  "roles": {}
                },
                {
                  "step_id": "PUBLIC-02",
                  "operation": "UPSERT_FACT",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:01:00Z",
                  "event_id": "EVENT-PUBLIC-FACT-01",
                  "roles": {
                    "PRIMARY_GOAL": "PUBLIC_GOAL_ALPHA",
                    "TARGET_ENTITY": "PUBLIC_ENTITY_A",
                    "STABLE_VALUE": "PUBLIC_STABLE_BLUE",
                    "CURRENT_AUTHORITY": "PUBLIC_AUTHORITY_V1"
                  }
                },
                {
                  "step_id": "PUBLIC-03",
                  "operation": "REQUEST_DECISION",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:02:00Z",
                  "event_id": "EVENT-PUBLIC-DECISION-01",
                  "roles": {
                    "PRIMARY_GOAL": "PUBLIC_GOAL_ALPHA",
                    "TARGET_ENTITY": "PUBLIC_ENTITY_A"
                  }
                }
              ]
            }
        """.trimIndent()
        val input = ProbeInputParser.parsePublic(raw)
        val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
        val stepResults = JSONArray()
        input.steps.forEach { stepResults.put(core.execute(it)) }
        val releaseBinding = JSONObject()
            .put("package_name", "org.scpc.r2.safeshare")
            .put("version_name", "0.1-demo")
            .put("version_code", "1")
            .put("signing_certificate_sha256", "b".repeat(64))
            .put("release_attestation_id", input.releaseAttestationId)

        val result = PublicProbeResultBuilder.build(
            input = input,
            rawInput = raw,
            stepResults = stepResults,
            evidenceIds = core.evidenceIds(),
            missionAdapterSha256 = "a".repeat(64),
            releaseBinding = releaseBinding,
            runId = "PUBLIC-UI-TEST",
        )

        assertEquals(Sha256.utf8(raw), result.getString("probe_input_sha256"))
        assertNotEquals(
            Sha256.utf8(CanonicalJson.encode(JSONObject(raw))),
            result.getString("probe_input_sha256"),
        )
        assertEquals(TOP_LEVEL_KEYS, result.keys().asSequence().toSet())
        assertTrue(result.getJSONArray("evidence_ids").length() > 0)
        assertEquals(3, result.getJSONArray("step_results").length())
        (0 until result.getJSONArray("step_results").length()).forEach { index ->
            val stepResult = result.getJSONArray("step_results").getJSONObject(index)
            assertEquals(STEP_RESULT_KEYS, stepResult.keys().asSequence().toSet())
            assertTrue(stepResult.getString("decision_state") in DECISION_STATES)
        }
        val action = result.getJSONArray("step_results").getJSONObject(2).getJSONObject("action")
        assertEquals(ACTION_KEYS, action.keys().asSequence().toSet())
        assertEquals("COMMITTED", action.getString("commit_state"))

        val withoutDigest = JSONObject(CanonicalJson.encode(result))
        val digest = withoutDigest.remove("result_digest").toString()
        assertEquals(digest, Sha256.canonicalJson(withoutDigest))
    }

    companion object {
        private val TOP_LEVEL_KEYS = setOf(
            "schema_version",
            "artifact_kind",
            "probe_contract_profile",
            "probe_pack_id",
            "instance_id",
            "request_nonce",
            "candidate_id",
            "mission_id",
            "operator_run_token",
            "run_id",
            "probe_input_sha256",
            "mission_adapter_sha256",
            "release_binding",
            "runtime_identity_start",
            "runtime_identity_end",
            "step_results",
            "evidence_ids",
            "result_digest",
        )
        private val STEP_RESULT_KEYS = setOf(
            "step_id",
            "event_id",
            "operation",
            "session_id",
            "virtual_time",
            "process_epoch",
            "network_state",
            "decision_state",
            "selected_context_ids",
            "invalidated_state_ids",
            "preserved_state_ids",
            "state_before_sha256",
            "state_after_sha256",
            "action",
            "tombstone_ids",
            "receipt_ids",
            "auto_check_evidence_ids",
        )
        private val ACTION_KEYS = setOf(
            "action_id",
            "idempotency_key",
            "commit_state",
            "outcome_id",
        )
        private val DECISION_STATES = setOf(
            "ACT",
            "ASK",
            "WAIT",
            "ABSTAIN",
            "PENDING",
            "FAILED",
            "CONFIRMED_COMPLETE",
            "NO_DECISION",
        )
    }
}
