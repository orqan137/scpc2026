package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputDocument
import org.scpc.r2.probe.Sha256
import java.util.UUID

/**
 * Pure result assembly kept outside the Activity so the public export contract
 * can be regression-tested on the JVM.
 */
internal object PublicProbeResultBuilder {
    fun build(
        input: ProbeInputDocument,
        rawInput: String,
        stepResults: JSONArray,
        evidenceIds: List<String>,
        missionAdapterSha256: String,
        releaseBinding: JSONObject,
        runId: String = "PUBLIC-UI-${UUID.randomUUID()}",
    ): JSONObject {
        val runtime = JSONObject()
            .put("model_backend_frozen_id", "NOT_USED")
            .put("release_attestation_id", input.releaseAttestationId)
            .put("cumulative_invocations", 0)
        val resultWithoutDigest = JSONObject()
            .put("schema_version", "3.0.0-draft")
            .put("artifact_kind", "scpc_probe_result")
            .put(
                "probe_contract_profile",
                "DACON_SIGNED_RUNNER_PROTECTED_COMPONENT_V3_DRAFT",
            )
            .put("probe_pack_id", input.probePackId)
            .put("instance_id", input.instanceId)
            .put("request_nonce", input.requestNonce)
            .put("candidate_id", input.candidateId)
            .put("mission_id", input.missionId)
            .put("operator_run_token", input.operatorRunToken)
            .put("run_id", runId)
            // Contract requires the imported bytes, including whitespace, not
            // the parsed/canonicalized JSON document.
            .put("probe_input_sha256", Sha256.utf8(rawInput))
            .put("mission_adapter_sha256", missionAdapterSha256)
            .put("release_binding", JSONObject(CanonicalJson.encode(releaseBinding)))
            .put("runtime_identity_start", JSONObject(CanonicalJson.encode(runtime)))
            .put("runtime_identity_end", JSONObject(CanonicalJson.encode(runtime)))
            .put("step_results", JSONArray(CanonicalJson.encode(stepResults)))
            .put("evidence_ids", JSONArray(evidenceIds.distinct()))
        return resultWithoutDigest.put(
            "result_digest",
            Sha256.canonicalJson(resultWithoutDigest),
        )
    }
}
