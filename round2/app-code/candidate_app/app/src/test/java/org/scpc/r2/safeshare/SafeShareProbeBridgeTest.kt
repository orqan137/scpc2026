package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputParser
import java.nio.file.Files

class SafeShareProbeBridgeTest {
    @Test
    fun bridgePreservesCoreSemanticsAndResultIdsEqualArchiveFilenames() {
        val root = Files.createTempDirectory("safeshare-bridge-test").toFile()
        try {
            val input = ProbeInputParser.parsePublic(PUBLIC_INPUT)
            val binding = SafeShareProbeBridge.publicBinding(input, "PUBLIC-UI-BRIDGE-TEST")
            val bridgedCore = SafeShareCore(InMemoryRelayStateStore(), "process-a")
            val directCore = SafeShareCore(InMemoryRelayStateStore(), "process-a")
            val archive = RunEvidenceArchive(root)
            val bridge = SafeShareProbeBridge(bridgedCore, archive)
            bridge.begin(binding)
            directCore.beginProbeRun(binding.runId)

            val bridgedResults = JSONArray()
            val directResults = JSONArray()
            input.steps.forEach { step ->
                bridgedResults.put(bridge.execute(binding, step))
                directResults.put(directCore.execute(step))
            }
            assertEquals(
                CanonicalJson.encode(directResults),
                CanonicalJson.encode(bridgedResults),
            )

            val ids = bridge.finish(binding)
            val snapshot = archive.latestCompleted()!!
            assertEquals(ids.toSet(), snapshot.records.keys)
            assertEquals(ids.size, snapshot.records.size)

            val result = PublicProbeResultBuilder.build(
                input = input,
                rawInput = PUBLIC_INPUT,
                stepResults = bridgedResults,
                evidenceIds = ids,
                missionAdapterSha256 = "a".repeat(64),
                releaseBinding = JSONObject()
                    .put("package_name", "org.scpc.r2.safeshare")
                    .put("version_name", "0.1-demo")
                    .put("version_code", "1")
                    .put("signing_certificate_sha256", "b".repeat(64))
                    .put("release_attestation_id", input.releaseAttestationId),
                runId = binding.runId,
            )
            val resultIds = result.getJSONArray("evidence_ids").let { array ->
                (0 until array.length()).map(array::getString)
            }
            assertEquals(ids, resultIds)
            assertEquals(resultIds.toSet(), snapshot.records.keys)
            snapshot.records.values.forEach { encoded ->
                assertFalse(encoded.contains("RAW_DELETE_ME"))
                assertFalse(encoded.contains("PUBLIC_STABLE_BLUE"))
                assertFalse(encoded.contains("PUBLIC_ENTITY_A"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aNewAssignmentDoesNotInheritPriorRunEvidenceIds() {
        val root = Files.createTempDirectory("safeshare-bridge-two-runs").toFile()
        try {
            val core = SafeShareCore(InMemoryRelayStateStore(), "process-a")
            val archive = RunEvidenceArchive(root)
            val bridge = SafeShareProbeBridge(core, archive)
            val firstInput = ProbeInputParser.parsePublic(PUBLIC_INPUT)
            val firstBinding = SafeShareProbeBridge.publicBinding(firstInput, "PUBLIC-RUN-ONE")
            bridge.begin(firstBinding)
            firstInput.steps.forEach { bridge.execute(firstBinding, it) }
            val firstIds = bridge.finish(firstBinding)

            val secondRaw = PUBLIC_INPUT
                .replace("PUBLIC-BRIDGE-INSTANCE-A", "PUBLIC-BRIDGE-INSTANCE-B")
                .replace("PUBLIC_NONCE_BRIDGE_0001", "PUBLIC_NONCE_BRIDGE_0002")
                .replace("PUBLIC_REFERENCE_BRIDGE_01", "PUBLIC_REFERENCE_BRIDGE_02")
                .replace("PUBLIC_SURFACE_BRIDGE_A", "PUBLIC_SURFACE_BRIDGE_B")
                .replace("EVENT-BRIDGE-", "EVENT-SECOND-")
            val secondInput = ProbeInputParser.parsePublic(secondRaw)
            val secondBinding = SafeShareProbeBridge.publicBinding(secondInput, "PUBLIC-RUN-TWO")
            bridge.begin(secondBinding)
            secondInput.steps.forEach { bridge.execute(secondBinding, it) }
            val secondIds = bridge.finish(secondBinding)

            assertEquals(8, firstIds.size)
            assertEquals(8, secondIds.size)
            assertTrue(firstIds.toSet().intersect(secondIds.toSet()).isEmpty())
            assertEquals(secondIds.toSet(), core.evidenceIds().toSet())
            assertEquals(secondIds.toSet(), archive.latestCompleted()!!.records.keys)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unicodeAndSharedLongPrefixEventIdsRemainDistinctInArchive() {
        val root = Files.createTempDirectory("safeshare-bridge-collision-ids").toFile()
        try {
            val sharedPrefix = "EVENT-LONG-" + "x".repeat(100)
            val raw = PUBLIC_INPUT
                .replace("EVENT-BRIDGE-RESET-01", "가")
                .replace("EVENT-BRIDGE-FACT-01", "나")
                .replace("EVENT-BRIDGE-DECISION-01", "${sharedPrefix}A")
                .replace("EVENT-BRIDGE-NETWORK-01", "${sharedPrefix}B")
            val input = ProbeInputParser.parsePublic(raw)
            val binding = SafeShareProbeBridge.publicBinding(input, "PUBLIC-COLLISION-ID-RUN")
            val core = SafeShareCore(InMemoryRelayStateStore(), "process-collision")
            val archive = RunEvidenceArchive(root)
            val bridge = SafeShareProbeBridge(core, archive)
            bridge.begin(binding)
            input.steps.forEach { bridge.execute(binding, it) }
            val ids = bridge.finish(binding)

            assertEquals(8, ids.size)
            assertEquals(8, ids.toSet().size)
            assertTrue(ids.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) })
            assertEquals(ids.toSet(), archive.latestCompleted()!!.records.keys)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun literalOldTransformedAliasCannotCollideWithUnicodeEventId() {
        val unicode = "가"
        val oldTransformedAlias = "_-${org.scpc.r2.probe.Sha256.utf8(unicode).take(16)}"

        assertTrue(oldTransformedAlias.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(SafeShareId.component(unicode) != SafeShareId.component(oldTransformedAlias))
    }

    companion object {
        private val PUBLIC_INPUT = """
            {
              "schema_version": "3.0.0-draft",
              "content_class": "PUBLIC_REHEARSAL_NOT_OFFICIAL",
              "probe_contract_profile": "DACON_SIGNED_RUNNER_PROTECTED_COMPONENT_V3_DRAFT",
              "probe_pack_id": "PUBLIC-BRIDGE-01",
              "instance_id": "PUBLIC-BRIDGE-INSTANCE-A",
              "request_nonce": "PUBLIC_NONCE_BRIDGE_0001",
              "operator_run_token": "PUBLIC_REFERENCE_BRIDGE_01",
              "candidate_id": "TEST_ONLY",
              "mission_id": "safe-share-test",
              "release_attestation_id": "ra-v1:2036053b4cdfe780d15be633acd3ccac8d1c4890728747aa2a9f60d45edddf0a",
              "surface_nonce": "PUBLIC_SURFACE_BRIDGE_A",
              "steps": [
                {
                  "step_id": "PUBLIC-01",
                  "operation": "RESET_AND_START",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:00:00Z",
                  "event_id": "EVENT-BRIDGE-RESET-01",
                  "roles": {}
                },
                {
                  "step_id": "PUBLIC-02",
                  "operation": "UPSERT_FACT",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:01:00Z",
                  "event_id": "EVENT-BRIDGE-FACT-01",
                  "roles": {
                    "PRIMARY_GOAL": "PUBLIC_GOAL_ALPHA",
                    "TARGET_ENTITY": "PUBLIC_ENTITY_A",
                    "STABLE_VALUE": "PUBLIC_STABLE_BLUE",
                    "EPHEMERAL_VALUE": "RAW_DELETE_ME",
                    "CURRENT_AUTHORITY": "PUBLIC_AUTHORITY_V1"
                  }
                },
                {
                  "step_id": "PUBLIC-03",
                  "operation": "REQUEST_DECISION",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:02:00Z",
                  "event_id": "EVENT-BRIDGE-DECISION-01",
                  "roles": {
                    "PRIMARY_GOAL": "PUBLIC_GOAL_ALPHA",
                    "TARGET_ENTITY": "PUBLIC_ENTITY_A"
                  }
                },
                {
                  "step_id": "PUBLIC-04",
                  "operation": "SET_NETWORK",
                  "session_id": "SESSION-A",
                  "virtual_time": "2026-01-01T00:03:00Z",
                  "event_id": "EVENT-BRIDGE-NETWORK-01",
                  "roles": {
                    "NETWORK_STATE": "OFFLINE",
                    "ONE_OFF_VALUE": "OFFLINE"
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
