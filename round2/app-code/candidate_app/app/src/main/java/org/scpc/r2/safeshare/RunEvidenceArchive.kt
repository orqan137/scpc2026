package org.scpc.r2.safeshare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.Sha256
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

data class EvidenceRunBinding(
    val assignmentId: String,
    val candidateId: String,
    val instanceId: String,
    val runId: String,
    val probePackId: String,
    val missionId: String,
    val releaseAttestationId: String,
    val entryPath: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("assignment_id", assignmentId)
        .put("candidate_id", candidateId)
        .put("instance_id", instanceId)
        .put("run_id", runId)
        .put("probe_pack_id", probePackId)
        .put("mission_id", missionId)
        .put("release_attestation_id", releaseAttestationId)
        .put("entry_path", entryPath)
}

data class EvidenceStepBinding(
    val stepId: String,
    val eventId: String,
    val operation: String,
    val sessionId: String,
    val virtualTime: String,
    val sensitiveRoleValues: Set<String>,
)

data class EvidenceArchiveSnapshot(
    val runId: String,
    val status: String,
    val records: Map<String, String>,
)

/**
 * App-private, per-run evidence journal.
 *
 * Official execution only writes to no-backup app-private storage. A later,
 * user-initiated public UI flow copies one completed run to a selected folder.
 * Evidence contains structured observations and hashes, never input roles,
 * production state JSON, shared text, or deleted values.
 */
class RunEvidenceArchive(private val root: File) {
    fun begin(binding: EvidenceRunBinding) {
        validateBinding(binding)
        val directory = runDirectory(binding.runId)
        val records = File(directory, RECORDS_DIRECTORY)
        check(records.mkdirs() || records.isDirectory) {
            "cannot create evidence archive: $records"
        }
        val metadataFile = File(directory, METADATA_FILE)
        if (metadataFile.exists()) {
            val existing = readObject(metadataFile)
            check(bindingMatches(existing.getJSONObject("run_binding"), binding)) {
                "run ID is already bound to another execution"
            }
            check(existing.getString("status") == STATUS_STARTED) {
                "completed or aborted evidence run cannot be restarted"
            }
            return
        }
        replaceJson(
            metadataFile,
            JSONObject()
                .put("schema_version", ARCHIVE_SCHEMA)
                .put("artifact_kind", "safeshare_evidence_run")
                .put("status", STATUS_STARTED)
                .put("started_at", Instant.now().toString())
                .put("run_binding", binding.toJson()),
        )
    }

    fun recordStep(
        binding: EvidenceRunBinding,
        step: EvidenceStepBinding,
        observation: JSONObject,
        requestedAt: String,
        finalStateAt: String,
    ) {
        val directory = requireStartedRun(binding)
        val receiptId = exactlyOneId(observation, "receipt_ids")
        val evidenceId = exactlyOneId(observation, "auto_check_evidence_ids")
        check(receiptId != evidenceId) { "receipt and evidence IDs must differ" }
        validateEvidenceId(receiptId)
        validateEvidenceId(evidenceId)

        val recordsDirectory = File(directory, RECORDS_DIRECTORY)
        val receipt = receiptJson(
            binding = binding,
            step = step,
            observation = observation,
            receiptId = receiptId,
            requestedAt = requestedAt,
            finalStateAt = finalStateAt,
        )
        val evidence = evidenceJson(
            binding = binding,
            step = step,
            observation = observation,
            evidenceId = evidenceId,
        )
        rejectSensitiveRoleLeak(receipt, step.sensitiveRoleValues, receiptId)
        rejectSensitiveRoleLeak(evidence, step.sensitiveRoleValues, evidenceId)

        writeOnceJson(File(recordsDirectory, "$receiptId.json"), receipt)
        writeOnceJson(File(recordsDirectory, "$evidenceId.json"), evidence)
    }

    fun finish(binding: EvidenceRunBinding, expectedIds: List<String>): List<String> {
        check(expectedIds.isNotEmpty()) { "finished run must reference evidence" }
        check(expectedIds.size == expectedIds.toSet().size) {
            "finished run repeats evidence IDs"
        }
        expectedIds.forEach(::validateEvidenceId)
        val directory = requireStartedRun(binding)
        validateRecordDirectory(directory, binding.runId, expectedIds.toSet())

        val metadataFile = File(directory, METADATA_FILE)
        val metadata = readObject(metadataFile)
            .put("status", STATUS_COMPLETED)
            .put("finished_at", Instant.now().toString())
            .put("record_count", expectedIds.size)
            .put("record_ids", JSONArray(expectedIds))
        replaceJson(metadataFile, metadata)
        replaceText(File(root, LATEST_COMPLETED_FILE), directory.name)
        return expectedIds.toList()
    }

    fun abort(binding: EvidenceRunBinding, reason: String) {
        val directory = runDirectory(binding.runId)
        val metadataFile = File(directory, METADATA_FILE)
        if (!metadataFile.isFile) return
        val metadata = readObject(metadataFile)
        if (!bindingMatches(metadata.getJSONObject("run_binding"), binding)) return
        if (metadata.optString("status") == STATUS_COMPLETED) return
        replaceJson(
            metadataFile,
            metadata
                .put("status", STATUS_ABORTED)
                .put("aborted_at", Instant.now().toString())
                .put("abort_reason", reason.take(256)),
        )
    }

    fun latestCompleted(): EvidenceArchiveSnapshot? {
        val pointer = File(root, LATEST_COMPLETED_FILE)
        if (!pointer.isFile) return null
        val directoryName = pointer.readText(Charsets.UTF_8).trim()
        check(directoryName.matches(Regex("[0-9a-f]{64}"))) {
            "invalid latest evidence pointer"
        }
        val directory = File(File(root, RUNS_DIRECTORY), directoryName)
        val metadata = readObject(File(directory, METADATA_FILE))
        check(metadata.getString("status") == STATUS_COMPLETED) {
            "latest evidence run is not complete"
        }
        val runId = metadata.getJSONObject("run_binding").getString("run_id")
        val expectedIds = jsonStrings(metadata.getJSONArray("record_ids"))
        val records = validateRecordDirectory(directory, runId, expectedIds.toSet())
        check(records.size == expectedIds.size) { "evidence archive record count mismatch" }
        return EvidenceArchiveSnapshot(
            runId = runId,
            status = STATUS_COMPLETED,
            records = expectedIds.associateWith { id -> records.getValue(id) },
        )
    }

    private fun receiptJson(
        binding: EvidenceRunBinding,
        step: EvidenceStepBinding,
        observation: JSONObject,
        receiptId: String,
        requestedAt: String,
        finalStateAt: String,
    ): JSONObject {
        val action = observation.optJSONObject("action")
        return JSONObject()
            .put("schema_version", RECORD_SCHEMA)
            .put("artifact_kind", "local_decision_receipt")
            .put("receipt_id", receiptId)
            .put("run_binding", binding.toJson())
            .put("step_id", step.stepId)
            .put("event_id", step.eventId)
            .put("operation", step.operation)
            .put("requested_at", requestedAt)
            .put("final_state_at", finalStateAt)
            .put("decision_state", observation.getString("decision_state"))
            .put(
                "action_commit_state",
                action?.optString("commit_state", "NONE") ?: "NONE",
            )
            .put(
                "attempts",
                JSONArray().put(
                    JSONObject()
                        .put("attempt_id", "attempt-${safeId(step.eventId)}-local-1")
                        .put("execution_kind", "LOCAL_PRODUCTION_CORE")
                        .put("started_at", requestedAt)
                        .put("ended_at", finalStateAt)
                        .put("status", "SUCCESS")
                        .put("retry", false),
                ),
            )
            .put("decision_invocations", 0)
            .put("cumulative_invocations", 0)
            .put("quota_exceeded", false)
            .put(
                "privacy",
                JSONObject()
                    .put("raw_roles_included", false)
                    .put("production_state_included", false)
                    .put("deleted_content_included", false),
            )
    }

    private fun evidenceJson(
        binding: EvidenceRunBinding,
        step: EvidenceStepBinding,
        observation: JSONObject,
        evidenceId: String,
    ): JSONObject {
        val action = observation.optJSONObject("action")
        return JSONObject()
            .put("schema_version", RECORD_SCHEMA)
            .put("artifact_kind", "observable_step_evidence")
            .put("evidence_id", evidenceId)
            .put("run_binding", binding.toJson())
            .put(
                "step",
                JSONObject()
                    .put("step_id", step.stepId)
                    .put("event_id", step.eventId)
                    .put("operation", step.operation)
                    .put("session_id", step.sessionId)
                    .put("virtual_time", step.virtualTime),
            )
            .put("process_epoch", observation.getInt("process_epoch"))
            .put("network_state", observation.getString("network_state"))
            .put("decision_state", observation.getString("decision_state"))
            .put("state_before_sha256", observation.getString("state_before_sha256"))
            .put("state_after_sha256", observation.getString("state_after_sha256"))
            .put("observation_sha256", Sha256.canonicalJson(observation))
            .put(
                "state_reference_digests",
                JSONObject()
                    .put(
                        "selected",
                        digestArray(observation.getJSONArray("selected_context_ids")),
                    )
                    .put(
                        "invalidated",
                        digestArray(observation.getJSONArray("invalidated_state_ids")),
                    )
                    .put(
                        "preserved",
                        digestArray(observation.getJSONArray("preserved_state_ids")),
                    )
                    .put(
                        "tombstones",
                        digestArray(observation.getJSONArray("tombstone_ids")),
                    ),
            )
            .put(
                "reference_counts",
                JSONObject()
                    .put("selected", observation.getJSONArray("selected_context_ids").length())
                    .put("invalidated", observation.getJSONArray("invalidated_state_ids").length())
                    .put("preserved", observation.getJSONArray("preserved_state_ids").length())
                    .put("tombstones", observation.getJSONArray("tombstone_ids").length()),
            )
            .put(
                "action",
                action?.let(::publicAction) ?: JSONObject.NULL,
            )
            .put(
                "privacy",
                JSONObject()
                    .put("raw_roles_included", false)
                    .put("production_state_included", false)
                    .put("deleted_content_included", false),
            )
    }

    private fun publicAction(action: JSONObject): JSONObject = JSONObject()
        .put("action_id", action.opt("action_id") ?: JSONObject.NULL)
        .put("idempotency_key", action.opt("idempotency_key") ?: JSONObject.NULL)
        .put("commit_state", action.optString("commit_state", "NONE"))
        .put("outcome_id", action.opt("outcome_id") ?: JSONObject.NULL)

    private fun digestArray(values: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until values.length()) {
            put(Sha256.utf8(values.getString(index)))
        }
    }

    private fun requireStartedRun(binding: EvidenceRunBinding): File {
        val directory = runDirectory(binding.runId)
        val metadata = readObject(File(directory, METADATA_FILE))
        check(bindingMatches(metadata.getJSONObject("run_binding"), binding)) {
            "evidence run binding mismatch"
        }
        check(metadata.getString("status") == STATUS_STARTED) {
            "evidence run is not writable"
        }
        return directory
    }

    private fun validateRecordDirectory(
        directory: File,
        runId: String,
        expectedIds: Set<String>,
    ): Map<String, String> {
        val recordsDirectory = File(directory, RECORDS_DIRECTORY)
        check(recordsDirectory.isDirectory) { "evidence record directory is missing" }
        val files = recordsDirectory.listFiles()?.sortedBy(File::getName).orEmpty()
        check(files.all { it.isFile && !it.name.startsWith(".") && it.extension == "json" }) {
            "evidence archive contains unsupported entries"
        }
        val records = linkedMapOf<String, String>()
        files.forEach { file ->
            check(file.length() > 0) { "evidence file is empty: ${file.name}" }
            val id = file.nameWithoutExtension
            validateEvidenceId(id)
            check(records.put(id, file.readText(Charsets.UTF_8)) == null) {
                "duplicate evidence ID: $id"
            }
            val value = readObject(file)
            val declaredId = when (value.getString("artifact_kind")) {
                "local_decision_receipt" -> value.getString("receipt_id")
                "observable_step_evidence" -> value.getString("evidence_id")
                else -> error("unknown evidence artifact kind: ${file.name}")
            }
            check(declaredId == id) { "evidence filename and JSON ID differ: $id" }
            check(value.getJSONObject("run_binding").getString("run_id") == runId) {
                "evidence file belongs to another run: $id"
            }
        }
        check(records.keys == expectedIds) {
            "archive IDs differ from result IDs: " +
                "missing=${expectedIds - records.keys} extra=${records.keys - expectedIds}"
        }
        return records
    }

    private fun runDirectory(runId: String): File =
        File(File(root, RUNS_DIRECTORY), Sha256.utf8(runId))

    private fun validateBinding(binding: EvidenceRunBinding) {
        check(binding.runId.isNotBlank()) { "run ID is blank" }
        check(binding.candidateId.isNotBlank()) { "candidate ID is blank" }
        check(binding.missionId.isNotBlank()) { "mission ID is blank" }
        check(binding.releaseAttestationId.isNotBlank()) {
            "release attestation is blank"
        }
    }

    private fun bindingMatches(value: JSONObject, binding: EvidenceRunBinding): Boolean =
        CanonicalJson.encode(value) == CanonicalJson.encode(binding.toJson())

    private fun exactlyOneId(observation: JSONObject, field: String): String {
        val values = observation.getJSONArray(field)
        check(values.length() == 1) { "$field must contain exactly one ID" }
        return values.getString(0)
    }

    private fun rejectSensitiveRoleLeak(
        value: JSONObject,
        sensitiveValues: Set<String>,
        evidenceId: String,
    ) {
        val sensitive = sensitiveValues.filter(String::isNotEmpty).toSet()
        if (sensitive.isEmpty()) return
        val leakPaths = mutableListOf<String>()
        collectSensitiveScalarPaths(value, "$", sensitive, leakPaths)
        check(leakPaths.isEmpty()) {
            "raw Probe role value leaked into $evidenceId at ${leakPaths.joinToString()}"
        }
    }

    private fun collectSensitiveScalarPaths(
        value: Any?,
        path: String,
        sensitiveValues: Set<String>,
        matches: MutableList<String>,
    ) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                collectSensitiveScalarPaths(value.opt(key), "$path.$key", sensitiveValues, matches)
            }
            is JSONArray -> (0 until value.length()).forEach { index ->
                collectSensitiveScalarPaths(value.opt(index), "$path[$index]", sensitiveValues, matches)
            }
            is String -> if (
                value in sensitiveValues &&
                !isRoleIndependentContractMetadata(path)
            ) {
                matches += path
            }
        }
    }

    /**
     * These fields are copied from the signed run/step envelope or are bounded
     * protocol enums and digests generated by this app. A synthetic role value
     * may legally equal one of them (for example TARGET_ENTITY == session_id or
     * CURRENT_AUTHORITY == "ASK"); equality there is not evidence of a leak.
     *
     * Free-form payload paths deliberately remain fail-closed. In particular,
     * action IDs/outcome IDs are checked while only action.commit_state is
     * treated as protocol metadata.
     */
    private fun isRoleIndependentContractMetadata(path: String): Boolean =
        path in ROLE_INDEPENDENT_METADATA_PATHS ||
            path.startsWith("$.run_binding.") ||
            path.startsWith("$.step.") ||
            path.startsWith("$.attempts[") ||
            path.startsWith("$.state_reference_digests.")

    private fun writeOnceJson(file: File, value: JSONObject) {
        check(!file.exists()) { "duplicate evidence filename stem: ${file.nameWithoutExtension}" }
        replaceText(file, CanonicalJson.encode(value))
    }

    private fun replaceJson(file: File, value: JSONObject) =
        replaceText(file, CanonicalJson.encode(value))

    private fun replaceText(file: File, text: String) {
        check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true) {
            "cannot create archive directory for $file"
        }
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            temporary.delete()
            error("cannot replace archive file: $file")
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("cannot publish archive file: $file")
        }
    }

    private fun readObject(file: File): JSONObject {
        check(file.isFile && file.length() > 0) { "missing or empty JSON: $file" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun validateEvidenceId(value: String) {
        check(EVIDENCE_ID.matches(value)) { "invalid evidence ID: $value" }
    }

    private fun safeId(value: String): String = SafeShareId.component(value)

    private fun jsonStrings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    companion object {
        private const val ARCHIVE_SCHEMA = "safeshare-run-archive-v1"
        private const val RECORD_SCHEMA = "safeshare-evidence-v1"
        private const val RUNS_DIRECTORY = "runs"
        private const val RECORDS_DIRECTORY = "records"
        private const val METADATA_FILE = "run.json"
        private const val LATEST_COMPLETED_FILE = "latest-completed.txt"
        private const val STATUS_STARTED = "STARTED"
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val STATUS_ABORTED = "ABORTED"
        private val EVIDENCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val ROLE_INDEPENDENT_METADATA_PATHS = setOf(
            "$.schema_version",
            "$.artifact_kind",
            "$.receipt_id",
            "$.evidence_id",
            "$.step_id",
            "$.event_id",
            "$.operation",
            "$.requested_at",
            "$.final_state_at",
            "$.decision_state",
            "$.action_commit_state",
            "$.network_state",
            "$.state_before_sha256",
            "$.state_after_sha256",
            "$.observation_sha256",
            "$.action.commit_state",
        )

        fun from(context: Context): RunEvidenceArchive =
            RunEvidenceArchive(File(context.noBackupFilesDir, "safe-share-evidence-spike"))
    }
}
