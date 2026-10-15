package org.scpc.r2.safeshare

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.Sha256

/**
 * Executes synthetic episode actions through the same production [SafeShareCore]
 * used by the Android UI. It never performs an external dispatch.
 */
class SafeShareScenarioExecutor(
    private val storeFactory: () -> RelayStateStore = { InMemoryRelayStateStore() },
    private val coreFactory: (RelayStateStore, String) -> SafeShareCore = { store, marker ->
        SafeShareCore(store, marker)
    },
) {
    fun execute(
        scenario: SafeShareScenario,
        store: RelayStateStore = storeFactory(),
    ): ScenarioRunTrace {
        require(scenario.synthetic) { "executor accepts synthetic scenarios only" }
        var processOrdinal = 0
        var processMarker = processMarker(scenario.scenarioId, processOrdinal)
        var core = coreFactory(store, processMarker)
        var checkpointed = false
        var activePayload: SafeSharePayload? = null
        val records = mutableListOf<ScenarioActionObservation>()

        scenario.episodes.forEach { episode ->
            episode.actions.forEach { action ->
                var actionResult: JSONObject? = null
                when (action) {
                    ScenarioAction.REVIEW_INTAKE -> {
                        activePayload = payload(scenario, episode)
                        actionResult = activePayload?.let(core::rememberReviewedPayload)
                            ?: core.productSnapshot()
                    }
                    ScenarioAction.CONFIRM_CONTEXT -> {
                        actionResult = updateContext(core, episode, activePayload, "CONTEXT_CONFIRMATION")
                        core.proposeRoute(episode.context.purposeId, episode.context.targetRoomId)
                        if (canConfirm(activePayload, episode.context.linkAccess)) {
                            actionResult = core.confirmPendingRoute()
                        }
                    }
                    ScenarioAction.EVALUATE_CANDIDATES -> {
                        actionResult = core.evaluateCandidateRooms(
                            episode.context.purposeId,
                            episode.candidateRoomIds,
                            readOnlyRooms = episode.readOnlyRoomIds.toSet(),
                        )
                    }
                    ScenarioAction.PROPOSE_DRAFT -> {
                        val current = activePayload ?: payload(scenario, episode)
                        require(current != null) { "${episode.episodeId} has no intake to propose" }
                        activePayload = current
                        core.rememberReviewedPayload(current)
                        updateContext(core, episode, current, "DRAFT_REVIEWED")
                        val before = core.productSnapshot()
                        val confirmedTarget = before.getJSONObject("route_rules")
                            .optString(episode.context.purposeId, "")
                        if (confirmedTarget != episode.context.targetRoomId) {
                            core.proposeRoute(episode.context.purposeId, episode.context.targetRoomId)
                            if (canConfirm(current, episode.context.linkAccess)) {
                                core.confirmPendingRoute()
                            }
                        }
                        core.evaluateCandidateRooms(
                            episode.context.purposeId,
                            episode.candidateRoomIds,
                            readOnlyRooms = episode.readOnlyRoomIds.toSet(),
                        )
                        actionResult = core.receiveSharedText(
                            current.eventId,
                            current.review.safePreview,
                        )
                    }
                    ScenarioAction.ADVANCE_SESSION -> {
                        actionResult = core.productSnapshot()
                    }
                    ScenarioAction.APPLY_CONTEXT_EVENT -> {
                        payload(scenario, episode)?.let { incoming ->
                            activePayload = incoming
                            core.rememberReviewedPayload(incoming)
                        }
                        actionResult = core.beginContextChange(
                            purpose = episode.context.purposeId,
                            targetRoomId = episode.context.targetRoomId,
                            roomDisplay = episode.context.targetRoomDisplay,
                            currentRole = episode.context.currentRole,
                            allowedScope = episode.context.allowedScope,
                            linkAccess = episode.context.linkAccess.name,
                        )
                    }
                    ScenarioAction.REQUEST_DECISION -> {
                        actionResult = core.productSnapshot()
                    }
                    ScenarioAction.CHECKPOINT_IF_HELD -> {
                        val snapshot = core.productSnapshot()
                        require(!snapshot.isNull("pending_context_change")) {
                            "checkpoint requested without pending context"
                        }
                        require(snapshot.getInt("held_draft_count") > 0) {
                            "checkpoint requested without held drafts"
                        }
                        checkpointed = true
                        actionResult = snapshot
                    }
                    ScenarioAction.RELAUNCH_IF_CHECKPOINTED -> {
                        require(checkpointed) { "relaunch requested without a checkpoint" }
                        processOrdinal += 1
                        processMarker = processMarker(scenario.scenarioId, processOrdinal)
                        core = coreFactory(store, processMarker)
                        actionResult = core.productSnapshot()
                    }
                    ScenarioAction.CONFIRM_PENDING_CONTEXT -> {
                        actionResult = core.revisePendingContextChange(
                            targetRoomId = episode.context.targetRoomId,
                            roomDisplay = episode.context.targetRoomDisplay,
                            currentRole = episode.context.currentRole,
                            allowedScope = episode.context.allowedScope,
                            linkAccess = episode.context.linkAccess.name,
                        )
                        if (canConfirm(activePayload, episode.context.linkAccess)) {
                            actionResult = core.confirmPendingContextChange()
                        }
                    }
                    ScenarioAction.RESUME_DRAFT -> {
                        core.evaluateCandidateRooms(
                            episode.context.purposeId,
                            episode.candidateRoomIds,
                            readOnlyRooms = episode.readOnlyRoomIds.toSet(),
                        )
                        actionResult = core.productSnapshot()
                    }
                }

                val snapshot = core.productSnapshot()
                val observed = bindObservation(
                    scenario = scenario,
                    episode = episode,
                    processMarker = processMarker,
                    snapshot = snapshot,
                    actionResult = actionResult,
                )
                records += ScenarioActionObservation(
                    index = records.size + 1,
                    afterAction = "${episode.episodeId}.${action.name}",
                    episodeId = episode.episodeId,
                    action = action,
                    processMarker = processMarker,
                    observed = observed,
                )
            }
        }

        return ScenarioRunTrace(
            scenarioId = scenario.scenarioId,
            inputSha256 = scenario.inputSha256,
            episodeCount = scenario.episodes.size,
            observations = records,
        )
    }

    private fun updateContext(
        core: SafeShareCore,
        episode: ScenarioEpisode,
        payload: SafeSharePayload?,
        reviewState: String,
    ): JSONObject = core.updateSafetyContext(
        purpose = episode.context.purposeId,
        roomDisplay = episode.context.targetRoomDisplay,
        currentRole = episode.context.currentRole,
        allowedScope = episode.context.allowedScope,
        linkAccess = episode.context.linkAccess.name,
        contentKind = payload?.review?.kind?.name ?: episode.intake.kind,
        reviewState = reviewState,
        roomId = episode.context.targetRoomId,
    )

    private fun canConfirm(
        payload: SafeSharePayload?,
        linkAccess: ScenarioLinkAccess,
    ): Boolean = payload?.review?.requiresLinkAccessConfirmation != true ||
        SafeSharePolicy.linkAccessConfirmed(linkAccess.name)

    private fun payload(
        scenario: SafeShareScenario,
        episode: ScenarioEpisode,
    ): SafeSharePayload? {
        val eventId = "synthetic-${scenario.scenarioId.lowercase()}-${episode.episodeId.lowercase()}"
        return when (val intake = episode.intake) {
            is ScenarioIntake.Text -> SafeSharePayload(
                eventId = eventId,
                review = SafeSharePolicy.review(ShareKind.TEXT, intake.content),
                privatePdfCopied = false,
            )
            is ScenarioIntake.Link -> SafeSharePayload(
                eventId = eventId,
                review = SafeShareLinkReview.review(
                    raw = intake.url,
                    host = runCatching { URI(intake.url).host }.getOrNull(),
                ),
                privatePdfCopied = false,
            )
            is ScenarioIntake.Pdf -> SafeSharePayload(
                eventId = eventId,
                review = SafeSharePolicy.review(
                    ShareKind.PDF,
                    "PDF: ${intake.assetRef.substringAfterLast('/')} " +
                        "(synthetic private fixture, ${intake.mimeType})",
                ),
                privatePdfCopied = true,
            )
            ScenarioIntake.None -> null
        }
    }

    private fun bindObservation(
        scenario: SafeShareScenario,
        episode: ScenarioEpisode,
        processMarker: String,
        snapshot: JSONObject,
        actionResult: JSONObject?,
    ): JSONObject = JSONObject(CanonicalJson.encode(snapshot))
        .put(
            "run",
            JSONObject()
                .put("scenario_id", scenario.scenarioId)
                .put("input_sha256", scenario.inputSha256)
                .put("process_epoch", snapshot.getInt("process_epoch"))
                .put("process_marker", processMarker),
        )
        .put(
            "input_binding",
            JSONObject()
                .put("persona_key", scenario.personaStyle.personaKey)
                .put("purpose_id", scenario.purpose.purposeId)
                .put("episode_id", episode.episodeId)
                .put("phase", episode.phase.name)
                .put("session", episode.session)
                .put("user_utterance_sha256", SafeSharePolicy.sha256(episode.userUtterance))
                .put("intake_kind", episode.intake.kind)
                .put("intake_sha256", Sha256.canonicalJson(episode.intake.toJson()))
                .put("context", episode.context.toJson())
                .put("candidate_room_ids", JSONArray(episode.candidateRoomIds)),
        )
        .put(
            "action_result",
            actionResult?.let { JSONObject(CanonicalJson.encode(it)) } ?: JSONObject.NULL,
        )

    private fun processMarker(scenarioId: String, ordinal: Int): String =
        "$scenarioId-synthetic-process-$ordinal"
}
