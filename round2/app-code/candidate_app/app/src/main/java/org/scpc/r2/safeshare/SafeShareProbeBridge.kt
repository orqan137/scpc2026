package org.scpc.r2.safeshare

import android.content.Context
import org.json.JSONObject
import org.scpc.r2.probe.ProbeInputDocument
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext
import java.time.Instant

/**
 * One connection path for the public UI and the protected starter adapter.
 *
 * Both callers use the same production core and the same evidence recorder;
 * only their runner-provided binding metadata differs.
 */
class SafeShareProbeBridge(
    private val core: SafeShareCore,
    private val archive: RunEvidenceArchive,
) {
    fun begin(binding: EvidenceRunBinding) {
        archive.begin(binding)
        core.beginProbeRun(binding.runId)
    }

    fun execute(binding: EvidenceRunBinding, step: ProbeInputStep): JSONObject {
        val requestedAt = Instant.now().toString()
        val observation = core.execute(step)
        val finalStateAt = Instant.now().toString()
        archive.recordStep(
            binding = binding,
            step = EvidenceStepBinding(
                stepId = step.stepId,
                eventId = step.eventId,
                operation = step.operation,
                sessionId = step.sessionId,
                virtualTime = step.virtualTime,
                sensitiveRoleValues = sensitiveRoleValues(step),
            ),
            observation = observation,
            requestedAt = requestedAt,
            finalStateAt = finalStateAt,
        )
        return observation
    }

    fun finish(binding: EvidenceRunBinding): List<String> =
        archive.finish(binding, core.evidenceIds())

    fun abort(binding: EvidenceRunBinding, reason: String) {
        archive.abort(binding, reason)
    }

    private fun sensitiveRoleValues(step: ProbeInputStep): Set<String> =
        SENSITIVE_ROLE_KEYS.mapNotNullTo(linkedSetOf()) { key ->
            if (
                step.operation == "SET_NETWORK" &&
                key == "ONE_OFF_VALUE"
            ) {
                // The public contract repeats the protocol enum (ONLINE/OFFLINE)
                // in ONE_OFF_VALUE for SET_NETWORK. It is control metadata, not
                // user content, and is intentionally observable as network_state.
                null
            } else if (step.roles.has(key) && !step.roles.isNull(key)) {
                step.roles.get(key).toString()
            } else {
                null
            }
        }

    companion object {
        private val SENSITIVE_ROLE_KEYS = setOf(
            "PRIMARY_GOAL",
            "TARGET_ENTITY",
            "DISTRACTOR_ENTITY",
            "STABLE_VALUE",
            "ONE_OFF_VALUE",
            "CURRENT_AUTHORITY",
            "REVOKED_SCOPE",
            "PRESERVED_SCOPE",
            "DELAYED_OUTCOME",
            "EPHEMERAL_VALUE",
        )

        fun from(context: Context): SafeShareProbeBridge =
            SafeShareProbeBridge(
                core = SafeShareCoreProvider.core(context),
                archive = RunEvidenceArchive.from(context),
            )

        fun protectedBinding(run: ProbeRunContext): EvidenceRunBinding =
            EvidenceRunBinding(
                assignmentId = run.assignmentId,
                candidateId = run.candidateId,
                instanceId = run.instanceId,
                runId = run.runId,
                probePackId = run.probePackId,
                missionId = run.missionId,
                releaseAttestationId = run.releaseAttestationId,
                entryPath = "PROTECTED_STARTER_ADAPTER",
            )

        fun publicBinding(input: ProbeInputDocument, runId: String): EvidenceRunBinding =
            EvidenceRunBinding(
                assignmentId = "PUBLIC-UI",
                candidateId = input.candidateId,
                instanceId = input.instanceId,
                runId = runId,
                probePackId = input.probePackId,
                missionId = input.missionId,
                releaseAttestationId = input.releaseAttestationId,
                entryPath = "PUBLIC_REHEARSAL_UI",
            )
    }
}
