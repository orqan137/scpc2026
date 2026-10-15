package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256
import java.nio.file.Files

class RunEvidenceArchiveTest {
    @Test
    fun completedRunPersistsExactlyThirteenReceiptAndEvidencePairs() {
        val root = Files.createTempDirectory("safeshare-evidence-test").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val binding = binding("run-26")
            archive.begin(binding)
            val evidenceIds = mutableListOf<String>()
            val receiptIds = mutableListOf<String>()

            (1..13).forEach { index ->
                val eventId = "EVENT-$index"
                val evidenceId = "evidence-$eventId"
                val receiptId = "receipt-$eventId"
                evidenceIds += evidenceId
                receiptIds += receiptId
                archive.recordStep(
                    binding = binding,
                    step = step(index, rawValue = "RAW-ROLE-VALUE-$index"),
                    observation = observation(
                        eventId = eventId,
                        roleDerivedId = "fact-RAW-ROLE-VALUE-$index-$eventId",
                    ),
                    requestedAt = "2026-07-30T00:00:${index.toString().padStart(2, '0')}Z",
                    finalStateAt = "2026-07-30T00:01:${index.toString().padStart(2, '0')}Z",
                )
            }

            val expectedIds = evidenceIds + receiptIds
            assertEquals(expectedIds, archive.finish(binding, expectedIds))

            val restored = RunEvidenceArchive(root).latestCompleted()
            assertNotNull(restored)
            restored!!
            assertEquals("run-26", restored.runId)
            assertEquals(26, restored.records.size)
            assertEquals(expectedIds.toSet(), restored.records.keys)
            restored.records.forEach { (id, encoded) ->
                assertTrue(encoded.isNotBlank())
                val value = JSONObject(encoded)
                val declaredId = when (value.getString("artifact_kind")) {
                    "local_decision_receipt" -> value.getString("receipt_id")
                    "observable_step_evidence" -> value.getString("evidence_id")
                    else -> error("unexpected artifact")
                }
                assertEquals(id, declaredId)
                assertEquals("run-26", value.getJSONObject("run_binding").getString("run_id"))
                (1..13).forEach { index ->
                    assertFalse(encoded.contains("RAW-ROLE-VALUE-$index"))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateStemAndResultSetMismatchFailClosed() {
        val root = Files.createTempDirectory("safeshare-evidence-duplicate").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val binding = binding("run-duplicate")
            archive.begin(binding)
            val evidence = observation("DUPLICATE", "opaque-state-id")
            archive.recordStep(
                binding,
                step(1, "RAW-ONE"),
                evidence,
                "2026-07-30T00:00:00Z",
                "2026-07-30T00:00:01Z",
            )

            assertThrows(IllegalStateException::class.java) {
                archive.recordStep(
                    binding,
                    step(2, "RAW-TWO"),
                    evidence,
                    "2026-07-30T00:00:02Z",
                    "2026-07-30T00:00:03Z",
                )
            }
            assertThrows(IllegalStateException::class.java) {
                archive.finish(
                    binding,
                    listOf(
                        "evidence-DUPLICATE",
                        "receipt-DUPLICATE",
                        "evidence-NOT-WRITTEN",
                    ),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun structuralPrivacyCheckAllowsRoleValueThatIsOnlyASubstringOfOpaqueIds() {
        val root = Files.createTempDirectory("safeshare-evidence-structural").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val binding = binding("run-structural-event")
            archive.begin(binding)
            archive.recordStep(
                binding = binding,
                step = step(1, rawValue = "EVENT"),
                observation = observation(
                    eventId = "EVENT-BOUNDARY-1",
                    roleDerivedId = "fact-EVENT-target-EVENT-BOUNDARY-1",
                ),
                requestedAt = "2026-07-30T00:00:00Z",
                finalStateAt = "2026-07-30T00:00:01Z",
            )

            val ids = listOf("evidence-EVENT-BOUNDARY-1", "receipt-EVENT-BOUNDARY-1")
            assertEquals(ids, archive.finish(binding, ids))
            val restored = archive.latestCompleted()
            assertNotNull(restored)
            assertEquals(ids.toSet(), restored!!.records.keys)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun structuralPrivacyCheckAllowsTargetToEqualSignedStepSessionId() {
        val root = Files.createTempDirectory("safeshare-evidence-role-equals-session").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val binding = binding("run-role-equals-session")
            val sharedValue = "SESSION-TARGET-SAME"
            val eventId = "METADATA-EQUALITY-1"
            archive.begin(binding)

            archive.recordStep(
                binding = binding,
                step = EvidenceStepBinding(
                    stepId = "METADATA-01",
                    eventId = eventId,
                    operation = "UPSERT_FACT",
                    sessionId = sharedValue,
                    virtualTime = "2026-07-30T00:00:00Z",
                    sensitiveRoleValues = setOf(sharedValue),
                ),
                observation = observation(eventId, "opaque-state-id"),
                requestedAt = "2026-07-30T00:00:00Z",
                finalStateAt = "2026-07-30T00:00:01Z",
            )

            val ids = listOf("evidence-$eventId", "receipt-$eventId")
            assertEquals(ids, archive.finish(binding, ids))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun structuralPrivacyCheckAllowsAuthorityToEqualDecisionProtocolEnum() {
        val root = Files.createTempDirectory("safeshare-evidence-role-equals-decision").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val binding = binding("run-role-equals-decision")
            val eventId = "METADATA-EQUALITY-2"
            archive.begin(binding)
            val observation = observation(eventId, "opaque-state-id")
                .put("decision_state", "ASK")
            observation.getJSONObject("action").put("commit_state", "PROPOSED")

            archive.recordStep(
                binding = binding,
                step = EvidenceStepBinding(
                    stepId = "METADATA-02",
                    eventId = eventId,
                    operation = "CORRECT_FACT",
                    sessionId = "SESSION-A",
                    virtualTime = "2026-07-30T00:00:00Z",
                    sensitiveRoleValues = setOf("ASK"),
                ),
                observation = observation,
                requestedAt = "2026-07-30T00:00:00Z",
                finalStateAt = "2026-07-30T00:00:01Z",
            )

            val ids = listOf("evidence-$eventId", "receipt-$eventId")
            assertEquals(ids, archive.finish(binding, ids))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun structuralPrivacyCheckStillRejectsAnExactSensitiveScalar() {
        val root = Files.createTempDirectory("safeshare-evidence-sensitive-scalar").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val binding = binding("run-sensitive-scalar")
            archive.begin(binding)
            val leaked = observation("LEAK-1", "opaque-state-id")
            leaked.getJSONObject("action").put("action_id", "RAW-ROLE-VALUE")

            assertThrows(IllegalStateException::class.java) {
                archive.recordStep(
                    binding = binding,
                    step = step(1, rawValue = "RAW-ROLE-VALUE"),
                    observation = leaked,
                    requestedAt = "2026-07-30T00:00:00Z",
                    finalStateAt = "2026-07-30T00:00:01Z",
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun delayedOutcomeDecisionArchivesChangedSelectionWithoutRawRoles() {
        val root = Files.createTempDirectory("safeshare-evidence-delayed-integration").toFile()
        try {
            val archive = RunEvidenceArchive(root)
            val core = SafeShareCore(InMemoryRelayStateStore(), "archive-process-a")
            val bridge = SafeShareProbeBridge(core, archive)
            val binding = binding("run-delayed-integration")
            bridge.begin(binding)

            bridge.execute(binding, probeStep(1, "RESET_AND_START", eventId = "RESET-1"))
            bridge.execute(
                binding,
                probeStep(
                    2,
                    "UPSERT_FACT",
                    JSONObject()
                        .put("PRIMARY_GOAL", "GOAL-OPAQUE")
                        .put("TARGET_ENTITY", "EVENT")
                        .put("STABLE_VALUE", "VALUE-OPAQUE")
                        .put("CURRENT_AUTHORITY", "AUTHORITY-INITIAL"),
                    eventId = "FACT-1",
                ),
            )
            bridge.execute(
                binding,
                probeStep(
                    3,
                    "REQUEST_DECISION",
                    JSONObject()
                        .put("PRIMARY_GOAL", "GOAL-OPAQUE")
                        .put("TARGET_ENTITY", "EVENT"),
                    eventId = "DECISION-1",
                ),
            )
            bridge.execute(
                binding,
                probeStep(
                    4,
                    "ADVANCE_TIME",
                    JSONObject().put("DELAYED_OUTCOME", "OUTCOME-OPAQUE"),
                    eventId = "LATE-1",
                ),
            )
            val changed = bridge.execute(
                binding,
                probeStep(
                    5,
                    "REQUEST_DECISION",
                    JSONObject()
                        .put("PRIMARY_GOAL", "GOAL-OPAQUE")
                        .put("TARGET_ENTITY", "EVENT"),
                    eventId = "DECISION-2",
                ),
            )
            val confirmed = bridge.execute(
                binding,
                probeStep(
                    6,
                    "REQUEST_DECISION",
                    JSONObject()
                        .put("PRIMARY_GOAL", "GOAL-OPAQUE")
                        .put("TARGET_ENTITY", "EVENT")
                        .put("CURRENT_AUTHORITY", "AUTHORITY-OPAQUE"),
                    eventId = "DECISION-3",
                ),
            )

            assertEquals("ASK", changed.getString("decision_state"))
            assertEquals(1, changed.getJSONArray("auto_check_evidence_ids").length())
            assertEquals("ACT", confirmed.getString("decision_state"))
            val finishedIds = bridge.finish(binding)
            assertEquals(12, finishedIds.size)

            val restored = archive.latestCompleted()!!
            val changedEvidenceId = "evidence-${SafeShareId.component("DECISION-2")}"
            val changedEvidence = JSONObject(restored.records.getValue(changedEvidenceId))
            assertEquals("ASK", changedEvidence.getString("decision_state"))
            assertEquals(2, changedEvidence.getJSONObject("reference_counts").getInt("selected"))
            restored.records.values.forEach { encoded ->
                assertFalse(encoded.contains("GOAL-OPAQUE"))
                assertFalse(encoded.contains("VALUE-OPAQUE"))
                assertFalse(encoded.contains("OUTCOME-OPAQUE"))
                assertFalse(encoded.contains("AUTHORITY-OPAQUE"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun binding(runId: String) = EvidenceRunBinding(
        assignmentId = "assignment-public",
        candidateId = "candidate-public",
        instanceId = "instance-public",
        runId = runId,
        probePackId = "pack-public",
        missionId = "safe-share",
        releaseAttestationId = "ra-v1:${"a".repeat(64)}",
        entryPath = "TEST",
    )

    private fun step(index: Int, rawValue: String) = EvidenceStepBinding(
        stepId = "PUBLIC-${index.toString().padStart(2, '0')}",
        eventId = "EVENT-$index",
        operation = "REQUEST_DECISION",
        sessionId = "SESSION-A",
        virtualTime = "2026-07-30T00:00:00Z",
        sensitiveRoleValues = setOf(rawValue),
    )

    private fun observation(eventId: String, roleDerivedId: String) = JSONObject()
        .put("step_id", "PUBLIC-01")
        .put("event_id", eventId)
        .put("operation", "REQUEST_DECISION")
        .put("session_id", "SESSION-A")
        .put("virtual_time", "2026-07-30T00:00:00Z")
        .put("process_epoch", 0)
        .put("network_state", "ONLINE")
        .put("decision_state", "ACT")
        .put("selected_context_ids", JSONArray().put(roleDerivedId))
        .put("invalidated_state_ids", JSONArray())
        .put("preserved_state_ids", JSONArray().put(roleDerivedId))
        .put("state_before_sha256", "b".repeat(64))
        .put("state_after_sha256", "c".repeat(64))
        .put(
            "action",
            JSONObject()
                .put("action_id", "local-draft-$eventId")
                .put("idempotency_key", "idem-$eventId")
                .put("commit_state", "COMMITTED")
                .put("outcome_id", "local-outcome-$eventId"),
        )
        .put("tombstone_ids", JSONArray())
        .put("receipt_ids", JSONArray().put("receipt-$eventId"))
        .put("auto_check_evidence_ids", JSONArray().put("evidence-$eventId"))

    private fun probeStep(
        index: Int,
        operation: String,
        roles: JSONObject = JSONObject(),
        eventId: String,
        sessionId: String = "SESSION-A",
    ): ProbeInputStep {
        val minute = index.toString().padStart(2, '0')
        val body = JSONObject()
            .put("step_id", "INTEGRATION-$index")
            .put("operation", operation)
            .put("session_id", sessionId)
            .put("virtual_time", "2026-07-30T00:$minute:00Z")
            .put("event_id", eventId)
            .put("roles", roles)
        val canonical = CanonicalJson.encode(body)
        return ProbeInputStep(
            index = index,
            stepId = "INTEGRATION-$index",
            operation = operation,
            sessionId = sessionId,
            virtualTime = "2026-07-30T00:$minute:00Z",
            eventId = eventId,
            roles = roles,
            canonicalJson = canonical,
            digest = Sha256.utf8(canonical),
        )
    }

}
