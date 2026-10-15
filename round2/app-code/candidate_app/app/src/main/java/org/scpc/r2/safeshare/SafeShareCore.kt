package org.scpc.r2.safeshare

import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

interface RelayStateStore {
    fun load(): JSONObject
    fun save(state: JSONObject)
}

class InMemoryRelayStateStore : RelayStateStore {
    private var encoded: String? = null

    override fun load(): JSONObject = encoded?.let(::JSONObject) ?: JSONObject()

    override fun save(state: JSONObject) {
        encoded = CanonicalJson.encode(state)
    }
}

/**
 * The one production state machine used by the ACTION_SEND surface, the public
 * rehearsal UI, and the protected Dacon adapter.
 *
 * It only commits app-local drafts. There is deliberately no network send,
 * share dispatch, account mutation, or other external action in this class.
 */
class SafeShareCore(
    private val store: RelayStateStore,
    private val processMarker: String,
    private val confirmedRouteReuseEnabled: Boolean = true,
    private val selectiveImpactRecoveryEnabled: Boolean = true,
) {
    /**
     * Binds run-level counters and evidence IDs to one Runner assignment.
     * A process relaunch of the same run keeps them; a new run clears only
     * these run-level indexes before its first RESET_AND_START.
     */
    fun beginProbeRun(runId: String) {
        require(runId.isNotBlank()) { "probe run ID must not be blank" }
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        if (state.optString("active_probe_run_id") != runId) {
            clearArray(state.getJSONArray("receipts"))
            clearArray(state.getJSONArray("evidence"))
            clearObject(state.getJSONObject("decision_traces"))
            state.put("decision_request_count", 0)
            state.put("probe_interaction_metrics", newProbeInteractionMetrics())
            state.put("active_probe_run_id", runId)
        }
        store.save(state)
    }

    fun receiveSharedText(eventId: String, text: String): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val processed = state.getJSONObject("processed_share_events")
        val envelopes = state.getJSONObject("share_envelopes")
        val existingEnvelopeId = processed.optString(eventId, "")
        if (existingEnvelopeId.isNotEmpty() && envelopes.has(existingEnvelopeId)) {
            store.save(state)
            return shareObservation(state, existingEnvelopeId, duplicate = true)
        }
        if (existingEnvelopeId.isNotEmpty()) processed.remove(eventId)

        val envelopeId = "share-${safeId(eventId)}"
        if (
            strings(state.getJSONArray("tombstoned_share_event_ids")).contains(eventId) ||
            strings(state.getJSONArray("tombstones")).contains(shareEventTombstoneId(eventId))
        ) {
            store.save(state)
            return JSONObject()
                .put("event_id", eventId)
                .put("status", "DELETED")
                .put("duplicate", false)
                .put("draft_id", JSONObject.NULL)
        }

        beginProductSession(state)
        pruneInactiveRouteRules(state)
        val purpose = state.optString("active_purpose", "")
        val confirmedTarget = state.getJSONObject("route_rules").optString(purpose, "")
        // A target correction is visible but not yet authoritative.  New
        // shares arriving in that interval must not inherit the prior target;
        // they remain ASK until the same confirmation that replans the older
        // drafts establishes the final route.
        val pendingRoute = state.optJSONObject("pending_route_change")
        val routeCorrectionPending = pendingRoute != null &&
            pendingRoute.optString("purpose") == purpose &&
            pendingRoute.optString("old_target").isNotEmpty() &&
            pendingRoute.optString("old_target") != pendingRoute.optString("new_target")
        // The same admission boundary applies to any unresolved E3/E4
        // context conflict.  A new share has no prior context binding, so it
        // must wait for the final authority instead of being stamped with the
        // superseded role/scope/room.
        val pendingContext = state.optJSONObject("pending_context_change")
        val contextChangePending = pendingContext != null &&
            pendingContext.optString("purpose") == purpose
        val target = if (
            confirmedRouteReuseEnabled &&
            !routeCorrectionPending &&
            !contextChangePending
        ) {
            confirmedTarget
        } else {
            ""
        }
        val descriptor = state.optJSONObject("active_payload_descriptor")
            ?.takeIf { it.optString("event_id") == eventId }
        val routeReused = target.isNotEmpty() &&
            !isRevoked(state, target) &&
            hasActiveRoutingFact(state, state.getJSONObject("facts"), purpose, target) &&
            payloadAllowsConfirmedRouteReuse(descriptor)
        val routeConfirmationRequiredCount = if (routeReused) 0 else 1
        val status = if (routeReused) "PROPOSED" else "ASK"
        val rawFactId = productRawFactId(eventId)
        val envelope = JSONObject()
            .put("event_id", eventId)
            .put("raw_text", text)
            .put("content_kind", descriptor?.optString("kind") ?: "TEXT")
            .put("content_sha256", descriptor?.optString("content_sha256") ?: SafeSharePolicy.sha256(text))
            .put("secret_redacted", descriptor?.optBoolean("secret_detected", false) ?: false)
            .put("purpose", purpose)
            .put("target", target)
            .put("status", status)
            .put("route_reused", routeReused)
            .put("route_confirmation_required_count", routeConfirmationRequiredCount)
            .put("local_annotation", "")
            .put("raw_fact_id", rawFactId)
        pendingContext
            ?.optString("exception_id", "")
            ?.takeIf(String::isNotEmpty)
            ?.let { envelope.put("pending_context_exception_id", it) }
        descriptor?.optString("private_pdf_copy_token", "")
            ?.takeIf(SafeSharePrivatePdfFiles::isValidToken)
            ?.let { envelope.put("private_pdf_copy_token", it) }
        putFact(
            facts = state.getJSONObject("facts"),
            factId = rawFactId,
            goal = purpose,
            target = target.ifEmpty { envelopeId },
            kind = "EPHEMERAL",
            value = text,
            authority = "ACTION_SEND_USER_INPUT",
            scope = "USER_SHARE_CONTENT",
            sessionId = state.getString("product_session_id"),
            eventId = eventId,
        )
        envelopes.put(envelopeId, envelope)
        state.getJSONArray("share_order").put(envelopeId)
        processed.put(eventId, envelopeId)
        recordProductInteraction(
            state = state,
            eventId = eventId,
            routeReused = routeReused,
            routeConfirmationRequiredCount = routeConfirmationRequiredCount,
        )

        if (status == "PROPOSED") {
            val routeFactId = ensureConfirmedRouteFact(state, purpose, target, eventId)
            linkProductDraftAndAction(
                state = state,
                envelope = envelope,
                rawFactId = rawFactId,
                routeFactId = routeFactId,
            )
            recordProductEpisode(state, "E2_REUSE")
        } else {
            recordProductEpisode(state, "E1_LEARN_ASK")
        }
        store.save(state)
        return shareObservation(state, envelopeId, duplicate = false)
    }

    fun setPurposeRoute(purpose: String, target: String): JSONObject {
        proposeRoute(purpose, target)
        return confirmPendingRoute()
    }

    /**
     * Persists only decision context that is safe to retain.  Incoming raw
     * content and PDF paths deliberately do not belong here: the UI passes a
     * redacted review summary to the core and keeps any copied PDF private.
     */
    fun updateSafetyContext(
        purpose: String,
        roomDisplay: String,
        currentRole: String,
        allowedScope: String,
        linkAccess: String,
        contentKind: String,
        reviewState: String,
        roomId: String = "",
        sharePermission: String = SafeSharePolicy.PERMISSION_CAN_SHARE,
    ): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val descriptorKind = state.optJSONObject("active_payload_descriptor")
            ?.optString("kind")
            .orEmpty()
        val resolvedKind = contentKind.trim().uppercase()
            .takeUnless { it.isEmpty() || it == "UNKNOWN" }
            ?: descriptorKind.ifEmpty { "UNKNOWN" }
        state.put(
            "safe_share_context",
            JSONObject()
                .put("purpose", purpose.trim())
                .put("room_id", roomId.trim())
                .put("room_display", roomDisplay.trim())
                .put("current_role", currentRole.trim())
                .put("allowed_scope", allowedScope.trim())
                .put("link_access", linkAccess.trim().uppercase())
                .put("share_permission", sharePermission.trim().uppercase())
                .put("content_kind", resolvedKind)
                .put("review_state", reviewState.trim().uppercase())
                .put("process_epoch", state.getInt("process_epoch")),
        )
        state.optJSONObject("active_payload_descriptor")?.let { descriptor ->
            if (descriptor.optString("kind") == ShareKind.LINK.name) {
                val normalizedAccess = linkAccess.trim().uppercase()
                descriptor.put(
                    "link_access",
                    if (SafeSharePolicy.linkAccessConfirmed(normalizedAccess)) {
                        normalizedAccess
                    } else {
                        "UNKNOWN"
                    },
                ).put("link_access_for_sha256", descriptor.optString("content_sha256"))
            }
        }
        store.save(state)
        return productSnapshot(state)
    }

    /**
     * Persists only a bounded, already-reviewed descriptor.  The safe preview
     * has already passed [SafeSharePolicy], so raw secrets never cross this
     * durable boundary.
     */
    fun rememberReviewedPayload(payload: SafeSharePayload): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        state.put(
            "active_payload_descriptor",
            JSONObject()
                .put("event_id", payload.eventId)
                .put("kind", payload.review.kind.name)
                .put("safe_preview", payload.review.safePreview.take(SafeSharePolicy.MAX_REVIEW_CHARS))
                .put("content_sha256", payload.review.contentSha256)
                .put("secret_detected", payload.review.secretDetected)
                .put(
                    "requires_link_access_confirmation",
                    payload.review.requiresLinkAccessConfirmation,
                )
                .put("recommendation", payload.review.recommendation)
                .put("private_pdf_copied", payload.privatePdfCopied)
                .put(
                    "private_pdf_copy_token",
                    payload.privatePdfCopyToken
                        ?.takeIf(SafeSharePrivatePdfFiles::isValidToken)
                        ?: JSONObject.NULL,
                )
                .put(
                    "link_access",
                    if (payload.review.kind == ShareKind.LINK) "UNKNOWN" else "NOT_APPLICABLE",
                )
                .put("link_access_for_sha256", payload.review.contentSha256),
        )
        store.save(state)
        return productSnapshot(state)
    }

    fun restoreReviewedPayload(): SafeSharePayload? {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val descriptor = state.optJSONObject("active_payload_descriptor")
            ?.takeIf { it.optString("event_id").isNotEmpty() }
        if (descriptor == null) {
            store.save(state)
            return null
        }
        val kind = runCatching { ShareKind.valueOf(descriptor.getString("kind")) }
            .getOrNull()
        if (kind == null) {
            store.save(state)
            return null
        }
        store.save(state)
        return SafeSharePayload(
            eventId = descriptor.getString("event_id"),
            review = SafeShareReview(
                kind = kind,
                safePreview = descriptor.optString("safe_preview", "(내용 없음)"),
                contentSha256 = descriptor.optString("content_sha256", ""),
                secretDetected = descriptor.optBoolean("secret_detected", false),
                requiresLinkAccessConfirmation = descriptor.optBoolean(
                    "requires_link_access_confirmation",
                    kind == ShareKind.LINK,
                ),
                recommendation = descriptor.optString("recommendation", "이 기기 안에서 공유할 내용을 준비합니다."),
            ),
            privatePdfCopied = descriptor.optBoolean("private_pdf_copied", false),
            privatePdfCopyToken = descriptor.optString("private_pdf_copy_token", "")
                .takeIf(SafeSharePrivatePdfFiles::isValidToken),
        )
    }

    /** Returns only the opaque filename bound to the requested local share. */
    fun privatePdfCopyToken(shareIdOrEventId: String? = null): String? {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val envelope = resolveShareEnvelopeId(state, shareIdOrEventId)
            ?.let { state.getJSONObject("share_envelopes").optJSONObject(it) }
        val descriptor = state.optJSONObject("active_payload_descriptor")
            ?.takeIf { active ->
                shareIdOrEventId.isNullOrBlank() ||
                    active.optString("event_id") == shareIdOrEventId
            }
        val token = sequenceOf(
            envelope?.optString("private_pdf_copy_token", ""),
            descriptor?.optString("private_pdf_copy_token", ""),
        ).filterNotNull().firstOrNull(SafeSharePrivatePdfFiles::isValidToken)
        store.save(state)
        return token
    }

    fun hasShareEnvelope(eventId: String): Boolean {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val found = resolveShareEnvelopeId(state, eventId) != null
        store.save(state)
        return found
    }

    /** Evaluates arbitrary opaque candidates against the confirmed route. */
    fun evaluateCandidateRooms(
        purpose: String,
        candidateRoomIds: List<String>,
        roomsOutsideScope: Set<String> = emptySet(),
        readOnlyRooms: Set<String> = emptySet(),
    ): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val candidates = candidateRoomIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val confirmed = state.getJSONObject("route_rules").optString(purpose, "")
        val selected = confirmed
            .takeIf {
                it in candidates &&
                    !isRevoked(state, it) &&
                    it !in roomsOutsideScope &&
                    it !in readOnlyRooms
            }
            .orEmpty()
        val excluded = JSONArray()
        candidates.filterNot { it == selected }.forEach { candidate ->
            excluded.put(
                JSONObject()
                    .put("room_id", candidate)
                    .put(
                        "reason_code",
                        when {
                            isRevoked(state, candidate) -> "REVOKED"
                            candidate in readOnlyRooms -> "ROLE_READ_ONLY_IN_ROOM"
                            candidate in roomsOutsideScope -> "MEMBER_OUTSIDE_ALLOWED_SCOPE"
                            else -> "NOT_CONFIRMED_FOR_PURPOSE"
                        },
                    ),
            )
        }
        val evaluation = JSONObject()
            .put("purpose", purpose)
            .put("candidate_room_ids", JSONArray(candidates))
            .put("selected_room_id", selected.ifEmpty { JSONObject.NULL })
            .put("excluded", excluded)
            .put("decision_state", if (selected.isEmpty()) "ASK" else "ACT")
        state.put("last_candidate_evaluation", evaluation)
        store.save(state)
        return JSONObject(CanonicalJson.encode(evaluation))
    }

    /**
     * Starts the product E3 path by comparing current authority and one-off
     * conditions with their durable predecessors.  Drafts remain reviewable
     * but are explicitly HELD until E4 confirms the changed fields.
     */
    fun beginContextChange(
        purpose: String,
        targetRoomId: String,
        roomDisplay: String,
        currentRole: String,
        allowedScope: String,
        linkAccess: String,
    ): JSONObject {
        require(purpose.isNotBlank()) { "purpose must not be blank" }
        require(targetRoomId.isNotBlank()) { "target room must not be blank" }
        require(roomDisplay.isNotBlank()) { "room display must not be blank" }
        require(currentRole.isNotBlank()) { "current role must not be blank" }
        require(allowedScope.isNotBlank()) { "allowed scope must not be blank" }
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        require(state.optJSONObject("pending_context_change") == null) {
            "이미 확인을 기다리는 조건 변경이 있습니다. E4에서 먼저 처리하세요."
        }
        val routeRules = state.getJSONObject("route_rules")
        val oldTarget = routeRules.optString(purpose, "")
        require(oldTarget.isNotEmpty()) { "E1에서 확인한 방 기준이 없습니다." }
        val oldContext = JSONObject(CanonicalJson.encode(state.getJSONObject("safe_share_context")))
        val descriptorKind = state.optJSONObject("active_payload_descriptor")
            ?.optString("kind")
            .orEmpty()
            .ifEmpty { oldContext.optString("content_kind", "UNKNOWN") }
        val normalizedLinkAccess = linkAccess.trim().uppercase()
        val newContext = JSONObject()
            .put("purpose", purpose.trim())
            .put("room_id", targetRoomId.trim())
            .put("room_display", roomDisplay.trim())
            .put("current_role", currentRole.trim())
            .put("allowed_scope", allowedScope.trim())
            .put("link_access", normalizedLinkAccess)
            .put("content_kind", descriptorKind)
            .put("review_state", "HELD_FOR_EXCEPTION")
            .put("process_epoch", state.getInt("process_epoch"))

        val changedFields = changedContextFields(
            oldTarget = oldTarget,
            oldContext = oldContext,
            newTarget = targetRoomId.trim(),
            newContext = newContext,
        )
        require(changedFields.isNotEmpty()) { "변경된 역할·범위·링크·방 기준이 없습니다." }

        if (state.optJSONObject("pending_context_change") == null) beginProductSession(state)
        val exceptionId = "context-change-${state.getInt("product_session_counter")}-${safeId(purpose)}"
        val affectedDraftIds = linkedSetOf<String>()
        val activeEventId = state.optJSONObject("active_payload_descriptor")
            ?.optString("event_id")
            .orEmpty()
        val activeDraftId = resolveShareEnvelopeId(state, activeEventId)
            ?.let { state.getJSONObject("share_envelopes").optJSONObject(it) }
            ?.optString("draft_id")
            .orEmpty()
        val linkOnlyChange = changedFields == linkedSetOf("LINK_ACCESS")
        val drafts = state.getJSONObject("drafts")
        drafts.keys().asSequence().toList().forEach { draftId ->
            val draft = drafts.getJSONObject(draftId)
            val isActivePurposeDraft = draft.optString("purpose") == purpose &&
                draft.optString("commit_state") != "CANCELLED"
            val isSelectiveMatch = isActivePurposeDraft &&
                draftDependsOnChangedContext(
                    draftId = draftId,
                    draft = draft,
                    changedFields = changedFields,
                    oldTarget = oldTarget,
                    oldContext = oldContext,
                    activeDraftId = activeDraftId,
                    activeEventId = activeEventId,
                    linkOnlyChange = linkOnlyChange,
                )
            val shouldAffect = if (selectiveImpactRecoveryEnabled) {
                isSelectiveMatch
            } else {
                isActivePurposeDraft
            }
            if (shouldAffect) {
                draft.put("hold_state", "HELD")
                    .put("held_by", exceptionId)
                    .put("held_changed_fields", JSONArray(changedFields.toList()))
                affectedDraftIds += draftId
            }
        }
        val envelopes = state.getJSONObject("share_envelopes")
        envelopes.keys().asSequence().toList().forEach { envelopeId ->
            val envelope = envelopes.getJSONObject(envelopeId)
            if (envelope.optString("draft_id") in affectedDraftIds) {
                envelope.put("status", "HELD")
                    .put("held_by", exceptionId)
                    .put("pending_target", targetRoomId.trim())
            }
        }

        val preservedFields = linkedSetOf(
            "PURPOSE",
            "SECRET_REDACTION_POLICY",
            "EXTERNAL_DISPATCH_BOUNDARY",
        )
        if ("TARGET_ROOM" !in changedFields) preservedFields += "TARGET_ROOM"
        if ("ROOM_PROFILE" !in changedFields) preservedFields += "ROOM_PROFILE"
        if ("CURRENT_ROLE" !in changedFields) preservedFields += "CURRENT_ROLE"
        if ("ALLOWED_SCOPE" !in changedFields) preservedFields += "ALLOWED_SCOPE"
        if ("LINK_ACCESS" !in changedFields) preservedFields += "LINK_ACCESS"

        state.put(
            "pending_context_change",
            JSONObject()
                .put("exception_id", exceptionId)
                .put("purpose", purpose.trim())
                .put("old_target", oldTarget)
                .put("new_target", targetRoomId.trim())
                .put("changed_fields", JSONArray(changedFields.toList()))
                .put("question_fields", JSONArray(changedFields.toList()))
                .put("affected_draft_ids", JSONArray(affectedDraftIds.toList()))
                .put("affected_draft_count", affectedDraftIds.size)
                .put(
                    "impact_selection_mode",
                    if (selectiveImpactRecoveryEnabled) "DEPENDENCY_SCOPED" else "PURPOSE_WIDE",
                )
                .put("preserved_fields", JSONArray(preservedFields.toList()))
                .put("old_context", oldContext)
                .put("new_context", newContext)
                .put("created_process_epoch", state.getInt("process_epoch"))
                .put("created_process_marker", processMarker),
        )
        if (oldTarget != targetRoomId.trim()) {
            state.put(
                "pending_route_change",
                JSONObject()
                    .put("purpose", purpose.trim())
                    .put("old_target", oldTarget)
                    .put("new_target", targetRoomId.trim())
                    .put("kind", "TARGET_CORRECTION"),
            )
        }
        state.put("safe_share_context", newContext)
        recordProductEpisode(state, "E3_EXCEPTION_ASK")
        store.save(state)
        return productSnapshot(state)
    }

    /**
     * Lets E4 amend only the pending, user-visible context before confirmation.
     * This is required when a changed link was UNKNOWN at E3 and the user
     * confirms its access boundary after the process relaunch.
     */
    fun revisePendingContextChange(
        targetRoomId: String,
        roomDisplay: String,
        currentRole: String,
        allowedScope: String,
        linkAccess: String,
    ): JSONObject {
        require(targetRoomId.isNotBlank()) { "target room must not be blank" }
        require(roomDisplay.isNotBlank()) { "room display must not be blank" }
        require(currentRole.isNotBlank()) { "current role must not be blank" }
        require(allowedScope.isNotBlank()) { "allowed scope must not be blank" }
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val pending = state.optJSONObject("pending_context_change")
            ?: error("복구할 보류 작업이 없습니다.")
        val oldTarget = pending.getString("old_target")
        val oldContext = pending.getJSONObject("old_context")
        val descriptorKind = state.optJSONObject("active_payload_descriptor")
            ?.optString("kind")
            .orEmpty()
            .ifEmpty { oldContext.optString("content_kind", "UNKNOWN") }
        val revisedContext = JSONObject()
            .put("purpose", pending.getString("purpose"))
            .put("room_id", targetRoomId.trim())
            .put("room_display", roomDisplay.trim())
            .put("current_role", currentRole.trim())
            .put("allowed_scope", allowedScope.trim())
            .put("link_access", linkAccess.trim().uppercase())
            .put("content_kind", descriptorKind)
            .put("review_state", "HELD_FOR_EXCEPTION")
            .put("process_epoch", state.getInt("process_epoch"))
        val changedFields = changedContextFields(
            oldTarget = oldTarget,
            oldContext = oldContext,
            newTarget = targetRoomId.trim(),
            newContext = revisedContext,
        )
        val questionFields = linkedSetOf<String>().apply {
            addAll(strings(pending.getJSONArray("question_fields")))
            addAll(changedFields)
        }

        // E4 is authoritative for the final, still-uncommitted context.  Its
        // changed-field set can expand (a role conflict becomes a target
        // correction) *or* narrow (the user returns to the original target).
        // Recompute the active impact set from the original pre-E3 context:
        // retaining only the old E3 set misses newly relevant drafts, while
        // retaining it forever needlessly replans drafts that no longer depend
        // on the final change.
        val previouslyAffectedDraftIds = strings(pending.getJSONArray("affected_draft_ids"))
        val affectedDraftIds = linkedSetOf<String>()
        val activeEventId = state.optJSONObject("active_payload_descriptor")
            ?.optString("event_id")
            .orEmpty()
        val activeDraftId = resolveShareEnvelopeId(state, activeEventId)
            ?.let { state.getJSONObject("share_envelopes").optJSONObject(it) }
            ?.optString("draft_id")
            .orEmpty()
        val linkOnlyChange = changedFields == linkedSetOf("LINK_ACCESS")
        val drafts = state.getJSONObject("drafts")
        drafts.keys().asSequence().toList().forEach { draftId ->
            val draft = drafts.getJSONObject(draftId)
            val isActivePurposeDraft = draft.optString("purpose") == pending.getString("purpose") &&
                draft.optString("commit_state") != "CANCELLED"
            val shouldAffect = if (selectiveImpactRecoveryEnabled) {
                isActivePurposeDraft && draftDependsOnChangedContext(
                    draftId = draftId,
                    draft = draft,
                    changedFields = changedFields,
                    oldTarget = oldTarget,
                    oldContext = oldContext,
                    activeDraftId = activeDraftId,
                    activeEventId = activeEventId,
                    linkOnlyChange = linkOnlyChange,
                )
            } else {
                isActivePurposeDraft
            }
            if (shouldAffect) {
                draft.put("hold_state", "HELD")
                    .put("held_by", pending.getString("exception_id"))
                    .put("held_changed_fields", JSONArray(changedFields.toList()))
                affectedDraftIds += draftId
            }
        }
        val noLongerAffectedDraftIds = previouslyAffectedDraftIds - affectedDraftIds
        noLongerAffectedDraftIds.forEach { draftId ->
            drafts.optJSONObject(draftId)?.let { draft ->
                if (draft.optString("held_by") == pending.getString("exception_id")) {
                    draft.remove("hold_state")
                    draft.remove("held_by")
                    draft.remove("held_changed_fields")
                }
            }
        }
        state.getJSONObject("share_envelopes").keys().asSequence().toList().forEach { envelopeId ->
            val envelope = state.getJSONObject("share_envelopes").getJSONObject(envelopeId)
            if (envelope.optString("draft_id") in affectedDraftIds) {
                envelope.put("status", "HELD")
                    .put("held_by", pending.getString("exception_id"))
                    .put("pending_target", targetRoomId.trim())
            } else if (
                envelope.optString("draft_id") in noLongerAffectedDraftIds &&
                envelope.optString("held_by") == pending.getString("exception_id")
            ) {
                envelope.put("status", "PROPOSED")
                envelope.remove("held_by")
                envelope.remove("pending_target")
            }
        }

        val preservedFields = linkedSetOf(
            "PURPOSE",
            "SECRET_REDACTION_POLICY",
            "EXTERNAL_DISPATCH_BOUNDARY",
        )
        listOf(
            "TARGET_ROOM",
            "ROOM_PROFILE",
            "CURRENT_ROLE",
            "ALLOWED_SCOPE",
            "LINK_ACCESS",
        ).filterNot(changedFields::contains).forEach(preservedFields::add)

        pending.put("new_target", targetRoomId.trim())
            .put("new_context", revisedContext)
            .put("changed_fields", JSONArray(changedFields.toList()))
            .put("question_fields", JSONArray(questionFields.toList()))
            .put("preserved_fields", JSONArray(preservedFields.toList()))
            .put("affected_draft_ids", JSONArray(affectedDraftIds.toList()))
            .put("affected_draft_count", affectedDraftIds.size)
        if (oldTarget == targetRoomId.trim()) {
            state.remove("pending_route_change")
        } else {
            state.put(
                "pending_route_change",
                JSONObject()
                    .put("purpose", pending.getString("purpose"))
                    .put("old_target", oldTarget)
                    .put("new_target", targetRoomId.trim())
                    .put("kind", "TARGET_CORRECTION"),
            )
        }
        state.put("safe_share_context", revisedContext)
        store.save(state)
        return productSnapshot(state)
    }

    /** Confirms only the fields captured by E3 and replans affected drafts. */
    fun confirmPendingContextChange(): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val pending = state.optJSONObject("pending_context_change")
            ?: return productSnapshot(state).also { store.save(state) }
        require(
            state.getInt("process_epoch") > pending.optInt("created_process_epoch", Int.MAX_VALUE),
        ) {
            "실제 앱 process 재시작이 확인되기 전에는 E4 복구를 완료할 수 없습니다."
        }
        val newContext = pending.getJSONObject("new_context")
        require(newContext.optString("room_display").isNotBlank()) {
            "대화방 용도를 확인하세요."
        }
        require(newContext.optString("current_role").isNotBlank()) {
            "현재 역할을 확인하세요."
        }
        require(newContext.optString("allowed_scope").isNotBlank()) {
            "허용 범위를 확인하세요."
        }
        val descriptor = state.optJSONObject("active_payload_descriptor")
        if (
            descriptor?.optBoolean("requires_link_access_confirmation", false) == true &&
            !SafeSharePolicy.linkAccessConfirmed(newContext.optString("link_access"))
        ) {
            error("변경된 링크 접근 상태를 확인하기 전에는 공유 준비를 다시 만들 수 없습니다.")
        }
        if (descriptor?.optBoolean("requires_link_access_confirmation", false) == true) {
            val contentSha256 = descriptor.optString("content_sha256")
            require(contentSha256.isNotEmpty()) { "링크 fingerprint가 없어 복구를 보류했습니다." }
            descriptor.put("link_access", newContext.getString("link_access").uppercase())
                .put("link_access_for_sha256", contentSha256)
            require(descriptor.optString("link_access_for_sha256") == contentSha256) {
                "링크 접근 확인이 현재 payload와 일치하지 않습니다."
            }
        }

        val purpose = pending.getString("purpose")
        val oldTarget = pending.getString("old_target")
        val newTarget = pending.getString("new_target")
        val exceptionId = pending.getString("exception_id")
        require(!isRevoked(state, newTarget)) {
            "철회된 대화방은 별도 재승인 없이 복구 대상으로 사용할 수 없습니다."
        }
        val changedFields = strings(pending.getJSONArray("changed_fields"))
        val affectedDraftIds = strings(pending.getJSONArray("affected_draft_ids")).toSet()
        if (changedFields.isEmpty()) {
            val drafts = state.getJSONObject("drafts")
            affectedDraftIds.forEach { draftId ->
                drafts.optJSONObject(draftId)?.put("hold_state", "RESOLVED")
                    ?.remove("held_by")
                drafts.optJSONObject(draftId)?.remove("held_changed_fields")
            }
            val routeFactId = ensureConfirmedRouteFact(
                state = state,
                purpose = purpose,
                target = newTarget,
                sourceEventId = "context-confirm-$exceptionId",
            )
            val replacementDraftIds = linkedSetOf<String>()
            val envelopes = state.getJSONObject("share_envelopes")
            envelopes.keys().asSequence().toList().forEach { envelopeId ->
                val envelope = envelopes.getJSONObject(envelopeId)
                val wasAffected = envelope.optString("draft_id") in affectedDraftIds
                val admittedDuringHold =
                    envelope.optString("pending_context_exception_id") == exceptionId
                if (wasAffected) {
                    envelope.put("status", "PROPOSED")
                    envelope.remove("pending_target")
                    envelope.remove("held_by")
                }
                if (admittedDuringHold) {
                    envelope.put("purpose", purpose)
                        .put("target", newTarget)
                        .put("status", "PROPOSED")
                    envelope.remove("pending_context_exception_id")
                    val rawFactId = envelope.optString("raw_fact_id")
                        .ifEmpty { productRawFactId(envelope.getString("event_id")) }
                    state.getJSONObject("facts").optJSONObject(rawFactId)?.let { rawFact ->
                        rawFact.put("goal", purpose)
                            .put("entity", newTarget)
                            .put("session_id", state.optString("product_session_id", ""))
                        linkProductDraftAndAction(
                            state = state,
                            envelope = envelope,
                            rawFactId = rawFactId,
                            routeFactId = routeFactId,
                        )
                        envelope.optString("draft_id")
                            .takeIf(String::isNotEmpty)
                            ?.let(replacementDraftIds::add)
                    }
                }
            }
            val recoveredContext = JSONObject(CanonicalJson.encode(newContext))
                .put("review_state", "E4_RECOVERED")
                .put("process_epoch", state.getInt("process_epoch"))
            state.put("safe_share_context", recoveredContext)
            state.put(
                "last_recovery",
                JSONObject()
                    .put("exception_id", exceptionId)
                    .put("changed_fields", JSONArray())
                    .put(
                        "confirmed_question_fields",
                        JSONArray(strings(pending.getJSONArray("question_fields"))),
                    )
                    .put("invalidated_draft_ids", JSONArray())
                    .put("replacement_draft_ids", JSONArray(replacementDraftIds.toList()))
                    .put("invalidated_draft_count", 0)
                    .put("replacement_draft_count", replacementDraftIds.size)
                    .put(
                        "preserved_fields",
                        JSONArray(strings(pending.getJSONArray("preserved_fields"))),
                    )
                    .put("old_target", oldTarget)
                    .put("new_target", newTarget),
            )
            state.remove("pending_context_change")
            state.remove("pending_route_change")
            recordProductEpisode(state, "E4_RECOVERED")
            store.save(state)
            return productSnapshot(state)
        }
        val invalidatedDraftIds = linkedSetOf<String>()
        val drafts = state.getJSONObject("drafts")
        affectedDraftIds.forEach { draftId ->
            drafts.optJSONObject(draftId)?.let { draft ->
                if (draft.optString("commit_state") != "CANCELLED") {
                    draft.put("commit_state", "CANCELLED")
                        .put("hold_state", "RESOLVED")
                        .put("invalidated_by", "context-change:$exceptionId")
                    cancelDraftParts(state, draftId, "context-change:$exceptionId")
                    invalidatedDraftIds += draftId
                }
            }
        }
        cancelActionsForDrafts(state, invalidatedDraftIds, "context-change:$exceptionId")

        if (oldTarget != newTarget) {
            supersedeConfirmedRouteFacts(state, purpose, oldTarget, newTarget)
        }
        state.getJSONObject("route_rules").put(purpose, newTarget)
        state.put("active_purpose", purpose)
        val routeFactId = ensureConfirmedRouteFact(
            state = state,
            purpose = purpose,
            target = newTarget,
            sourceEventId = "context-confirm-$exceptionId",
        )

        val replacementDraftIds = linkedSetOf<String>()
        val envelopes = state.getJSONObject("share_envelopes")
        envelopes.keys().asSequence().toList().forEach { envelopeId ->
            val envelope = envelopes.getJSONObject(envelopeId)
            val wasAffected = envelope.optString("draft_id") in affectedDraftIds
            val admittedDuringHold =
                envelope.optString("pending_context_exception_id") == exceptionId
            if (wasAffected || admittedDuringHold) {
                envelope.put("purpose", purpose)
                    .put("target", newTarget)
                    .put("status", "PROPOSED")
                envelope.remove("pending_target")
                envelope.remove("held_by")
                envelope.remove("pending_context_exception_id")
                val rawFactId = envelope.optString("raw_fact_id")
                    .ifEmpty { productRawFactId(envelope.getString("event_id")) }
                state.getJSONObject("facts").optJSONObject(rawFactId)?.let { rawFact ->
                    rawFact.put("goal", purpose)
                        .put("entity", newTarget)
                        .put("session_id", state.optString("product_session_id", ""))
                    linkProductDraftAndAction(
                        state = state,
                        envelope = envelope,
                        rawFactId = rawFactId,
                        routeFactId = routeFactId,
                        revisionToken = exceptionId.takeIf { wasAffected },
                    )
                    envelope.optString("draft_id")
                        .takeIf(String::isNotEmpty)
                        ?.let(replacementDraftIds::add)
                }
            }
        }

        val recoveredContext = JSONObject(CanonicalJson.encode(newContext))
            .put(
                "content_kind",
                descriptor?.optString("kind")
                    .orEmpty()
                    .ifEmpty { newContext.optString("content_kind", "UNKNOWN") },
            )
            .put("review_state", "E4_RECOVERED")
            .put("process_epoch", state.getInt("process_epoch"))
        descriptor?.let {
            if (it.optString("kind") == ShareKind.LINK.name) {
                it.put("link_access", recoveredContext.optString("link_access"))
                    .put("link_access_for_sha256", it.optString("content_sha256"))
            }
        }
        state.put("safe_share_context", recoveredContext)
        state.put(
            "last_recovery",
            JSONObject()
                .put("exception_id", exceptionId)
                .put("changed_fields", JSONArray(changedFields))
                .put(
                    "confirmed_question_fields",
                    JSONArray(strings(pending.getJSONArray("question_fields"))),
                )
                .put("invalidated_draft_ids", JSONArray(invalidatedDraftIds.toList()))
                .put("replacement_draft_ids", JSONArray(replacementDraftIds.toList()))
                .put("invalidated_draft_count", invalidatedDraftIds.size)
                .put("replacement_draft_count", replacementDraftIds.size)
                .put(
                    "preserved_fields",
                    JSONArray(strings(pending.getJSONArray("preserved_fields"))),
                )
                .put("old_target", oldTarget)
                .put("new_target", newTarget),
        )
        state.remove("pending_context_change")
        state.remove("pending_route_change")
        recordProductEpisode(state, "E4_RECOVERED")
        store.save(state)
        return productSnapshot(state)
    }

    fun proposeRoute(purpose: String, target: String): JSONObject {
        require(purpose.isNotBlank()) { "purpose must not be blank" }
        require(target.isNotBlank()) { "target must not be blank" }
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val oldTarget = state.getJSONObject("route_rules").optString(purpose, "")
        val correction = oldTarget.isNotEmpty() && oldTarget != target
        if (
            correction &&
            state.optJSONObject("pending_route_change") == null
        ) {
            beginProductSession(state)
        }
        state.put(
            "pending_route_change",
            JSONObject()
                .put("purpose", purpose)
                .put("old_target", oldTarget)
                .put("new_target", target)
                .put(
                    "kind",
                    if (oldTarget.isEmpty()) "INITIAL_ROUTE" else "TARGET_CORRECTION",
                ),
        )
        recordProductEpisode(
            state,
            if (oldTarget.isEmpty()) "E1_ROUTE_CONFIRMATION_ASK" else "E3_EXCEPTION_ASK",
        )
        latestEnvelopeId(state)?.let { envelopeId ->
            val envelope = state.getJSONObject("share_envelopes").getJSONObject(envelopeId)
            if (envelope.optString("purpose").isEmpty() || envelope.optString("purpose") == purpose) {
                envelope.put("purpose", purpose)
                envelope.put("pending_target", target)
                envelope.put("status", "ASK")
            }
        }
        store.save(state)
        return productSnapshot(state)
    }

    fun confirmPendingRoute(): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val pending = state.optJSONObject("pending_route_change")
            ?: return productSnapshot(state).also { store.save(state) }
        val descriptor = state.optJSONObject("active_payload_descriptor")
        if (descriptor?.optBoolean("requires_link_access_confirmation", false) == true) {
            val contentSha256 = descriptor.optString("content_sha256")
            require(contentSha256.isNotEmpty()) { "링크 fingerprint가 없어 방 기준 확인을 보류했습니다." }
            require(SafeSharePolicy.linkAccessConfirmed(descriptor.optString("link_access"))) {
                "현재 링크의 접근 범위를 확인하기 전에는 공유를 준비할 수 없습니다."
            }
            require(descriptor.optString("link_access_for_sha256") == contentSha256) {
                "링크 접근 확인이 현재 payload와 일치하지 않습니다."
            }
        }
        val purpose = pending.getString("purpose")
        val oldTarget = pending.optString("old_target", "")
        val newTarget = pending.getString("new_target")
        val correction = oldTarget.isNotEmpty() && oldTarget != newTarget
        if (
            correction &&
            state.optString("episode") != "E4_RELAUNCH_PENDING"
        ) {
            beginProductSession(state)
        }

        state.getJSONObject("route_rules").put(purpose, newTarget)
        removeString(state.getJSONArray("revoked_targets"), newTarget)
        if (!strings(state.getJSONArray("revoked_scope_labels")).contains(newTarget)) {
            removeString(state.getJSONArray("revoked_scopes"), newTarget)
        }
        state.put("active_purpose", purpose)
        if (correction) {
            supersedeConfirmedRouteFacts(state, purpose, oldTarget, newTarget)
            cancelDraftsForPurposeAndTarget(
                state = state,
                purpose = purpose,
                target = oldTarget,
                reason = "confirmed-target-correction",
            )
        }
        val routeFactId = ensureConfirmedRouteFact(
            state = state,
            purpose = purpose,
            target = newTarget,
            sourceEventId = "route-confirm-${safeId(purpose)}-${safeId(newTarget)}",
        )

        val envelopes = state.getJSONObject("share_envelopes")
        envelopes.keys().asSequence().toList().forEach { envelopeId ->
            val envelope = envelopes.getJSONObject(envelopeId)
            if (envelope.optString("purpose") == purpose) {
                envelope.put("target", newTarget)
                envelope.remove("pending_target")
                envelope.put("status", "PROPOSED")
                val rawFactId = envelope.optString("raw_fact_id")
                    .ifEmpty { productRawFactId(envelope.getString("event_id")) }
                state.getJSONObject("facts").optJSONObject(rawFactId)?.let { rawFact ->
                    rawFact.put("goal", purpose)
                        .put("entity", newTarget)
                        .put("session_id", state.optString("product_session_id", ""))
                    linkProductDraftAndAction(
                        state = state,
                        envelope = envelope,
                        rawFactId = rawFactId,
                        routeFactId = routeFactId,
                    )
                }
            }
        }
        state.remove("pending_route_change")
        // E4 is a distinct return/recovery session. A real process relaunch
        // with a pending correction already opened that fourth session in
        // ensureProcessEpoch, so the transition above does not open a fifth.
        recordProductEpisode(state, if (correction) "E4_RECOVERED" else "E1_LEARNED")
        if (correction) state.put("last_correction", "$oldTarget->$newTarget")
        store.save(state)
        return productSnapshot(state)
    }

    fun correctTarget(purpose: String, newTarget: String): JSONObject =
        proposeRoute(purpose, newTarget)

    fun revokeTarget(target: String): JSONObject {
        require(target.isNotBlank()) { "target must not be blank" }
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        if (state.optString("episode") != "E3_SCOPE_REVOKED") beginProductSession(state)
        appendUnique(state.getJSONArray("revoked_scopes"), target)
        appendUnique(state.getJSONArray("revoked_targets"), target)

        val routes = state.getJSONObject("route_rules")
        routes.keys().asSequence().toList().forEach { purpose ->
            if (routes.optString(purpose) == target) {
                routes.remove(purpose)
                supersedeConfirmedRouteFacts(state, purpose, target, "")
            }
        }
        val drafts = state.getJSONObject("drafts")
        val cancelledDraftIds = linkedSetOf<String>()
        drafts.keys().asSequence().toList().forEach { draftId ->
            val draft = drafts.getJSONObject(draftId)
            if (draft.optString("target") == target) {
                draft.put("commit_state", "CANCELLED")
                draft.put("invalidated_by", "scope-revocation")
                cancelDraftParts(state, draftId, "scope-revocation")
                cancelledDraftIds += draftId
            }
        }
        cancelActionsForDrafts(state, cancelledDraftIds, "scope-revocation")
        recordProductEpisode(state, "E3_SCOPE_REVOKED")
        store.save(state)
        return productSnapshot(state)
    }

    fun productSnapshot(): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        store.save(state)
        return productSnapshot(state)
    }

    fun memorySnapshot(): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        store.save(state)
        return memorySnapshot(state)
    }

    fun deleteShareMemory(shareIdOrEventId: String? = null): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        val envelopeId = resolveShareEnvelopeId(state, shareIdOrEventId)
        val deleted = envelopeId?.let {
            deleteShareEnvelope(
                state = state,
                envelopeId = it,
                reason = "user-memory-delete",
            )
        } ?: emptySet()
        val requestedEventId = shareIdOrEventId?.trim().orEmpty()
        val descriptorDeleted = if (
            envelopeId == null &&
            requestedEventId.isNotEmpty() &&
            state.optJSONObject("active_payload_descriptor")
                ?.optString("event_id") == requestedEventId
        ) {
            state.remove("active_payload_descriptor")
            appendUnique(state.getJSONArray("tombstoned_share_event_ids"), requestedEventId)
            appendUnique(state.getJSONArray("tombstones"), shareEventTombstoneId(requestedEventId))
            true
        } else {
            false
        }
        store.save(state)
        return memorySnapshot(state)
            .put("delete_requested_id", shareIdOrEventId ?: JSONObject.NULL)
            .put("deleted", envelopeId != null || descriptorDeleted)
            .put("deleted_state_ids", JSONArray(deleted.toList().sorted()))
    }

    fun resetAllUserData(): JSONObject {
        val state = normalized(store.load())
        ensureProcessEpoch(state)
        resetRunState(state)
        store.save(state)
        return memorySnapshot(state).put("reset_completed", true)
    }

    fun execute(step: ProbeInputStep): JSONObject {
        val state = normalized(store.load())
        val before = Sha256.canonicalJson(state)
        // Capture the persisted predecessor before a new process marker can
        // advance its epoch.  This keeps the public state-hash chain
        // continuous across a real kill/relaunch transition.
        ensureProcessEpoch(state)
        val invalidated = linkedSetOf<String>()
        val preserved = linkedSetOf<String>()
        val selected = linkedSetOf<String>()
        var decision = "NO_DECISION"
        var action: JSONObject? = null
        var actionRecordEventId: String? = null
        val receiptId = "receipt-${safeId(step.eventId)}"
        val evidenceId = "evidence-${safeId(step.eventId)}"
        val autoEvidenceIds = linkedSetOf(evidenceId)

        val facts = state.getJSONObject("facts")
        val tombstones = state.getJSONArray("tombstones")
        val actions = state.getJSONObject("actions")
        val requestedGoal = role(step.roles, "PRIMARY_GOAL")
        val activeGoal = state.optString("active_purpose", "")
        val preliminaryGoal = requestedGoal ?: activeGoal
        val target = role(step.roles, "TARGET_ENTITY")
            ?: state.getJSONObject("route_rules").optString(preliminaryGoal, "")
                .ifEmpty { "default" }
        val goalForLookup = resolveGoalForTarget(
            state = state,
            facts = facts,
            requestedGoal = requestedGoal,
            activeGoal = activeGoal,
            target = target,
        ).orEmpty()

        when (step.operation) {
            "RESET_AND_START" -> resetRunState(state, preserveProbeRunHistory = true)

            "UPSERT_FACT" -> {
                val resolvedGoal = goalForLookup
                val incomingScope = role(step.roles, "PRESERVED_SCOPE") ?: "LOCAL_DRAFT"
                val upsertAuthority = role(step.roles, "CURRENT_AUTHORITY")
                val inputFactValues = factValues(step.roles)
                val insertedFactIds = linkedSetOf<String>()
                if (
                    resolvedGoal.isEmpty() ||
                    target == "default" ||
                    upsertAuthority == null ||
                    inputFactValues.isEmpty()
                ) {
                    decision = "ABSTAIN"
                } else if (isScopeRevoked(state, resolvedGoal, target, incomingScope)) {
                    invalidated += scopeRevocationId(resolvedGoal, target, incomingScope)
                    decision = "ABSTAIN"
                } else {
                    inputFactValues.forEach { (kind, value) ->
                        val factId = factId(target, kind, step.eventId)
                        if (
                            isFactTombstoned(
                                state = state,
                                factId = factId,
                                goal = resolvedGoal,
                                target = target,
                                kind = kind,
                                value = value,
                            )
                        ) {
                            invalidated += tombstoneId(factId)
                            decision = "ABSTAIN"
                        } else {
                            putFact(
                                facts = facts,
                                factId = factId,
                                goal = resolvedGoal,
                                target = target,
                                kind = kind,
                                value = value,
                                authority = checkNotNull(upsertAuthority),
                                scope = incomingScope,
                                sessionId = step.sessionId,
                                eventId = step.eventId,
                            )
                            insertedFactIds += factId
                            preserved += factId
                        }
                    }
                }
                if (resolvedGoal.isNotEmpty() && insertedFactIds.isNotEmpty()) {
                    state.put("active_purpose", resolvedGoal)
                    if (
                        insertedFactIds.any { factId ->
                            facts.getJSONObject(factId).optString("kind") == "STABLE"
                        }
                    ) {
                        state.getJSONObject("route_rules").put(resolvedGoal, target)
                    }
                }
                role(step.roles, "DELAYED_OUTCOME")
                    ?.takeIf { insertedFactIds.isNotEmpty() }
                    ?.let { pendingValue ->
                        val pendingId = "pending-outcome-${safeId(step.eventId)}"
                        state.getJSONObject("pending_delayed_outcomes").put(
                            pendingId,
                            JSONObject()
                                .put("value", pendingValue)
                                .put("goal", resolvedGoal)
                                .put("entity", target)
                                .put("registered_by_event_id", step.eventId)
                                .put("registered_evidence_id", evidenceId)
                                .put("registered_at", step.virtualTime)
                                .put("dependency_ids", JSONArray(insertedFactIds.toList()))
                                .put("status", "PENDING"),
                        )
                        appendUnique(state.getJSONArray("pending_delayed_outcome_order"), pendingId)
                        preserved += pendingId
                    }
            }

            "ADVANCE_SESSION" -> {
                facts.keys().asSequence().toList().forEach { factId ->
                    val fact = facts.getJSONObject(factId)
                    if (
                        fact.optString("status", "ACTIVE") == "ACTIVE" &&
                        fact.optString("kind") == "ONE_OFF" &&
                        fact.optString("session_id") != step.sessionId
                    ) {
                        fact.put("status", "EXPIRED")
                        fact.put("expired_in_session", step.sessionId)
                        invalidated += factId
                    }
                }
                val expiredFactIds = invalidated.toSet()
                invalidatePendingOutcomesDependingOn(state, expiredFactIds)
                    .forEach(invalidated::add)
                invalidateDeliveredOutcomesDependingOn(state, expiredFactIds)
                    .forEach(invalidated::add)
                cancelActionsDependingOn(
                    state = state,
                    invalidatedIds = expiredFactIds,
                    reason = "one-off-session-expired",
                ).forEach(invalidated::add)
                role(step.roles, "DISTRACTOR_ENTITY")?.let { distractor ->
                    val distractorId = factId(distractor, "DISTRACTOR", step.eventId)
                    if (!facts.has(distractorId)) {
                        putFact(
                            facts = facts,
                            factId = distractorId,
                            goal = goalForLookup,
                            target = distractor,
                            kind = "DISTRACTOR",
                            value = distractor,
                            authority = role(step.roles, "CURRENT_AUTHORITY")
                                ?: "UNSPECIFIED_DISTRACTOR",
                            scope = "DISTRACTOR_CONTEXT",
                            sessionId = step.sessionId,
                            eventId = step.eventId,
                        )
                    }
                    preserved += distractorId
                }
                if (goalForLookup.isNotEmpty()) state.put("active_purpose", goalForLookup)
                currentFactIds(facts, goalForLookup, target)
                    .forEach(preserved::add)
            }

            "REQUEST_DECISION" -> {
                val traces = state.getJSONObject("decision_traces")
                val priorTrace = traces.optJSONObject(step.eventId)
                if (priorTrace != null) {
                    strings(priorTrace.getJSONArray("selected_context_ids")).forEach(selected::add)
                    strings(priorTrace.getJSONArray("cause_evidence_ids"))
                        .forEach(autoEvidenceIds::add)
                    decision = priorTrace.getString("decision_state")
                    actionRecordEventId = priorTrace.optString("action_record_event_id", "")
                        .takeIf(String::isNotEmpty)
                    action = actionRecordEventId?.let(actions::optJSONObject)?.let(::publicAction)
                } else {
                    state.put(
                        "decision_request_count",
                        state.getInt("decision_request_count") + 1,
                    )
                    val decisionGoal = goalForLookup
                    state.put("last_decision_goal", decisionGoal)
                    state.put("last_decision_target", target)
                    if (decisionGoal.isNotEmpty() && target != "default") {
                        actionableFactIds(state, facts, decisionGoal, target).forEach(selected::add)
                    }
                    var delayedOutcomeId = if (decisionGoal.isNotEmpty() && target != "default") {
                        latestOutcomeId(
                            state = state,
                            goal = decisionGoal,
                            target = target,
                        )
                    } else {
                        null
                    }
                    var delayedOutcome = delayedOutcomeId?.let {
                        state.getJSONObject("delayed_outcomes").optJSONObject(it)
                    }
                    if (
                        delayedOutcome != null &&
                        (
                            delayedOutcome.optString("goal", "").isEmpty() ||
                                delayedOutcome.optString("entity", "").isEmpty()
                            )
                    ) {
                        val canClaimUnscopedOutcome = selected.any { it.startsWith("fact-") } &&
                            decisionGoal.isNotEmpty() &&
                            target != "default"
                        if (canClaimUnscopedOutcome) {
                            if (delayedOutcome.optString("goal", "").isEmpty()) {
                                delayedOutcome.put("goal", decisionGoal)
                            }
                            if (delayedOutcome.optString("entity", "").isEmpty()) {
                                delayedOutcome.put("entity", target)
                            }
                            delayedOutcome.put("claimed_by_event_id", step.eventId)
                        } else {
                            delayedOutcomeId = null
                            delayedOutcome = null
                        }
                    }
                    delayedOutcomeId?.let(selected::add)
                    listOf(
                        delayedOutcome?.optString("source_evidence_id", "").orEmpty(),
                        delayedOutcome?.optString("registered_evidence_id", "").orEmpty(),
                    ).filter(String::isNotEmpty).forEach(autoEvidenceIds::add)

                    val outcomeNeedsConfirmation = delayedOutcome != null &&
                        delayedOutcome.optString("review_state", "UNREVIEWED") != "ACKNOWLEDGED"
                    val confirmsOutcome = outcomeNeedsConfirmation &&
                        role(step.roles, "CURRENT_AUTHORITY") != null
                    if (confirmsOutcome) {
                        delayedOutcome?.put("review_state", "ACKNOWLEDGED")
                            ?.put("acknowledged_by_event_id", step.eventId)
                            ?.put("acknowledged_evidence_id", evidenceId)
                            ?.put("acknowledged_process_epoch", state.getInt("process_epoch"))
                    }

                    decision = when {
                        state.getString("network") != "ONLINE" -> "WAIT"
                        isRevoked(state, target) -> "ABSTAIN"
                        outcomeNeedsConfirmation && !confirmsOutcome -> "ASK"
                        selected.none { it.startsWith("fact-") } -> "ASK"
                        else -> "ACT"
                    }
                    if (outcomeNeedsConfirmation && !confirmsOutcome && decision == "ASK") {
                        delayedOutcome?.put("review_state", "REVIEW_REQUIRED")
                            ?.put("review_requested_by_event_id", step.eventId)
                            ?.put("review_request_evidence_id", evidenceId)
                    }
                    if (decision == "ACT") {
                        val bindings = state.getJSONObject("decision_action_bindings")
                        val boundEventId = bindings.optString(step.eventId, "")
                        action = if (boundEventId.isNotEmpty()) {
                            actionRecordEventId = boundEventId
                            actions.optJSONObject(boundEventId)?.let(::publicAction)
                        } else {
                            val recovered = commitNextProposedAction(
                                state = state,
                                selectedIds = selected,
                                reconciledByEventId = step.eventId,
                            )
                            if (recovered != null) {
                                actionRecordEventId = recovered.first
                                bindings.put(step.eventId, recovered.first)
                                publicAction(recovered.second)
                            } else {
                                actionRecordEventId = step.eventId
                                bindings.put(step.eventId, step.eventId)
                                actionFor(step.eventId, state)
                            }
                        }
                    }
                    recordDecisionTrace(
                        state = state,
                        step = step,
                        decision = decision,
                        selectedIds = selected,
                        causeOutcomeId = delayedOutcomeId,
                        causeEvidenceIds = autoEvidenceIds.filterNot { it == evidenceId },
                        actionRecordEventId = actionRecordEventId,
                    )
                }
            }

            "CORRECT_FACT" -> {
                val existingIds = factIdsForTarget(facts, target, activeOnly = false)
                val resolvedGoal = goalForLookup
                val existingIdsForGoal = existingIds.filter { factId ->
                    resolvedGoal.isEmpty() ||
                        facts.getJSONObject(factId).optString("goal") == resolvedGoal
                }
                val (kind, value) = correctionValue(
                    roles = step.roles,
                    target = target,
                    existingFactIds = existingIdsForGoal,
                    facts = facts,
                )
                val factId = factId(target, kind, step.eventId)
                val correctionScope = role(step.roles, "PRESERVED_SCOPE") ?: "LOCAL_DRAFT"
                val correctionAuthority = role(step.roles, "CURRENT_AUTHORITY")
                val hasCorrectionValue = listOf(
                    "STABLE_VALUE",
                    "ONE_OFF_VALUE",
                    "EPHEMERAL_VALUE",
                ).any { role(step.roles, it) != null }
                val validCorrectionRequest = resolvedGoal.isNotEmpty() &&
                    target != "default" &&
                    correctionAuthority != null &&
                    hasCorrectionValue
                val revokedScope = isScopeRevoked(
                    state,
                    resolvedGoal,
                    target,
                    correctionScope,
                )
                val tombstoned = isFactTombstoned(
                    state = state,
                    factId = factId,
                    goal = resolvedGoal,
                    target = target,
                    kind = kind,
                    value = value,
                )
                if (validCorrectionRequest && !revokedScope && !tombstoned) {
                    currentFactIds(facts, resolvedGoal, target)
                        .filter { facts.getJSONObject(it).optString("kind", "STABLE") == kind }
                        .forEach { oldId ->
                            facts.getJSONObject(oldId)
                                .put("status", "SUPERSEDED")
                                .put("superseded_by_event", step.eventId)
                            invalidated += oldId
                        }
                    val supersededFactIds = invalidated.toSet()
                    invalidatePendingOutcomesDependingOn(state, supersededFactIds)
                        .forEach(invalidated::add)
                    invalidateDeliveredOutcomesDependingOn(state, supersededFactIds)
                        .forEach(invalidated::add)
                    putFact(
                        facts = facts,
                        factId = factId,
                        goal = resolvedGoal,
                        target = target,
                        kind = kind,
                        value = value,
                        authority = checkNotNull(correctionAuthority),
                        scope = correctionScope,
                        sessionId = step.sessionId,
                        eventId = step.eventId,
                    )
                    if (resolvedGoal.isNotEmpty()) {
                        state.put("active_purpose", resolvedGoal)
                        if (kind == "STABLE") {
                            state.getJSONObject("route_rules").put(resolvedGoal, target)
                        }
                    }
                    latestOutcomeId(state, resolvedGoal, target)?.let { outcomeId ->
                        state.getJSONObject("delayed_outcomes").optJSONObject(outcomeId)?.let { outcome ->
                            if (outcome.optString("review_state") != "ACKNOWLEDGED") {
                                outcome.put("review_state", "ACKNOWLEDGED")
                                    .put("acknowledged_by_event_id", step.eventId)
                                    .put("acknowledged_evidence_id", evidenceId)
                                    .put(
                                        "acknowledged_process_epoch",
                                        state.getInt("process_epoch"),
                                    )
                                preserved += outcomeId
                                outcome.optString("source_evidence_id", "")
                                    .takeIf(String::isNotEmpty)
                                    ?.let(autoEvidenceIds::add)
                            }
                        }
                    }
                    cancelActionsDependingOn(
                        state = state,
                        invalidatedIds = invalidated,
                        reason = "authority-correction",
                    ).forEach(invalidated::add)
                    preserved += factId
                    val draftId = createDraft(
                        state = state,
                        sourceEventId = step.eventId,
                        purpose = resolvedGoal,
                        target = target,
                        preservedScope = correctionScope,
                        dependencyIds = listOf(factId),
                    )
                    action = proposalFor(step.eventId, state, draftId)
                    state.getJSONObject("action_dependencies")
                        .put(step.eventId, JSONArray().put(factId))
                    decision = "ASK"
                } else {
                    when {
                        !validCorrectionRequest -> Unit
                        revokedScope -> invalidated += scopeRevocationId(
                            resolvedGoal,
                            target,
                            correctionScope,
                        )
                        tombstoned -> invalidated += tombstoneId(factId)
                    }
                    decision = "ABSTAIN"
                }
            }

            "REVOKE_SCOPE" -> {
                val revokedScope = role(step.roles, "REVOKED_SCOPE")
                val preservedScope = role(step.roles, "PRESERVED_SCOPE") ?: ""
                val revocationGoal = goalForLookup
                val validRevocation = revokedScope != null &&
                    revocationGoal.isNotEmpty() &&
                    target != "default"
                val candidates = if (!validRevocation || revokedScope == preservedScope) {
                    emptyList()
                } else {
                    revocationCandidates(
                        state = state,
                        facts = facts,
                        purpose = revocationGoal,
                        target = target,
                        revokedScope = checkNotNull(revokedScope),
                        preservedScope = preservedScope,
                    )
                }
                if (validRevocation) {
                    preserveCurrentScopeState(
                        state = state,
                        facts = facts,
                        purpose = revocationGoal,
                        target = target,
                        preservedScope = preservedScope,
                        preserved = preserved,
                    )
                }
                val allCandidatesExact = candidates.isNotEmpty() && candidates.all { candidate ->
                    isExactRevocationCandidate(
                        state = state,
                        facts = facts,
                        candidate = candidate,
                        revokedScope = checkNotNull(revokedScope),
                    )
                }
                val canApplyCandidates = candidates.size == 1 || allCandidatesExact
                val acceptedRevocationIntent = validRevocation &&
                    revokedScope != preservedScope &&
                    (candidates.isEmpty() || canApplyCandidates)
                if (acceptedRevocationIntent) {
                    recordScopeRevocationRule(
                        state = state,
                        goal = revocationGoal,
                        target = target,
                        scope = checkNotNull(revokedScope),
                    ).let(preserved::add)
                    appendUnique(
                        state.getJSONArray("revoked_scope_labels"),
                        checkNotNull(revokedScope),
                    )
                    appendUnique(
                        state.getJSONArray("revoked_scopes"),
                        checkNotNull(revokedScope),
                    )
                }
                when {
                    !validRevocation || revokedScope == preservedScope -> decision = "ABSTAIN"
                    canApplyCandidates -> {
                        candidates.forEach { candidate ->
                            if (candidate.startsWith(FACT_CANDIDATE_PREFIX)) {
                                val factId = candidate.removePrefix(FACT_CANDIDATE_PREFIX)
                                facts.getJSONObject(factId)
                                    .put("status", "REVOKED")
                                    .put("revoked_scope", revokedScope)
                                invalidated += factId
                                invalidatePendingOutcomesDependingOn(state, setOf(factId))
                                    .forEach(invalidated::add)
                                invalidateDeliveredOutcomesDependingOn(state, setOf(factId))
                                    .forEach(invalidated::add)
                                cancelActionsDependingOn(
                                    state,
                                    setOf(factId),
                                    reason = "scope-revoked",
                                ).forEach(invalidated::add)
                            } else {
                                val partId = candidate.removePrefix(PART_CANDIDATE_PREFIX)
                                val part = state.getJSONObject("draft_parts")
                                    .getJSONObject(partId)
                                part.put("status", "REVOKED")
                                    .put("scope_label", revokedScope)
                                    .put("revoked_by_event", step.eventId)
                                invalidated += partId
                                markDraftForScopedReplan(state, part, partId)
                            }
                        }
                    }
                    candidates.isEmpty() -> decision = "ABSTAIN"
                    else -> decision = "ASK"
                }
            }

            "DELETE_FACT" -> {
                if (goalForLookup.isEmpty() || target == "default") {
                    decision = "ABSTAIN"
                } else {
                    recordFactDeletionRules(
                        state = state,
                        goal = goalForLookup,
                        target = target,
                        roles = step.roles,
                    ).forEach(preserved::add)
                    val deletionIds = deletionFactIds(
                        facts = facts,
                        target = target,
                        goal = goalForLookup,
                        roles = step.roles,
                    )
                    deletionIds.forEach { factId ->
                        val fact = facts.getJSONObject(factId)
                        val kind = fact.optString("kind", "STABLE")
                        val value = fact.optString("value")
                        if (kind == "EPHEMERAL") {
                            deleteShareEnvelopesLinkedToFact(
                                state = state,
                                factId = factId,
                                sourceEventId = fact.optString("source_event_id", ""),
                                reason = "probe-source-deletion",
                            ).forEach(invalidated::add)
                        }
                        appendUnique(state.getJSONArray("tombstoned_fact_ids"), factId)
                        appendUnique(
                            state.getJSONArray("tombstoned_fact_fingerprints"),
                            factFingerprint(
                                fact.optString("goal", goalForLookup),
                                fact.optString("entity", target),
                                kind,
                                value,
                            ),
                        )
                        facts.remove(factId)
                        invalidated += factId
                        appendUnique(tombstones, tombstoneId(factId))
                    }
                    if (deletionIds.isNotEmpty()) {
                        invalidatePendingOutcomesDependingOn(state, deletionIds.toSet())
                            .forEach(invalidated::add)
                        invalidateDeliveredOutcomesDependingOn(state, deletionIds.toSet())
                            .forEach(invalidated::add)
                        cancelActionsDependingOn(
                            state = state,
                            invalidatedIds = deletionIds.toSet(),
                            reason = "source-deletion",
                        ).forEach(invalidated::add)
                    }
                    currentFactIds(facts, goalForLookup, target).forEach(preserved::add)
                }
            }

            "SET_NETWORK" -> state.put("network", requestedNetwork(step.roles))

            "PROCESS_KILL_RELAUNCH" -> {
                preserveDurableWorkState(state, preserved)
                latestOutcomeId(state, "", "")?.let(preserved::add)
                decision = latestLifecycleDecision(state)
            }

            "REPLAY_EVENT" -> {
                val originalEventId = protocolRole(step.roles, "REPLAY_OF_EVENT_ID")
                val actionEventId = originalEventId?.let {
                    resolveActionRecordEventId(state, it)
                }
                action = actionEventId?.let(actions::optJSONObject)?.let {
                    publicAction(it)
                }
                val tracedDecision = originalEventId?.let {
                    state.getJSONObject("decision_traces")
                        .optJSONObject(it)
                        ?.takeIf { trace ->
                            trace.optInt("reset_generation", -1) ==
                                state.getInt("probe_reset_generation")
                        }
                        ?.optString("decision_state", "")
                        ?.takeIf(String::isNotEmpty)
                }
                decision = when (action?.optString("commit_state")) {
                    "COMMITTED" -> "CONFIRMED_COMPLETE"
                    "PROPOSED" -> "PENDING"
                    "CANCELLED" -> "ABSTAIN"
                    else -> tracedDecision ?: "ABSTAIN"
                }
            }

            "DELIVER_OUT_OF_ORDER" -> {
                currentFactIds(facts, "", "").forEach(preserved::add)
                decision = "ABSTAIN"
            }

            "ADVANCE_TIME" -> {
                val explicitOutcome = role(step.roles, "DELAYED_OUTCOME")
                val explicitGoal = role(step.roles, "PRIMARY_GOAL")
                val explicitTarget = role(step.roles, "TARGET_ENTITY")
                val (correlationGoal, correlationTarget) = delayedOutcomeCorrelation(
                    state = state,
                    facts = facts,
                    explicitGoal = explicitGoal,
                    explicitTarget = explicitTarget,
                )
                val pendingSelection = selectPendingDelayedOutcome(
                    state = state,
                    requestedGoal = correlationGoal,
                    requestedTarget = correlationTarget,
                )
                val pending = pendingSelection.descriptor
                val priorDecisionTrace = if (pending == null) {
                    state.getJSONObject("decision_traces").optJSONObject(
                        state.getJSONObject("probe_interaction_metrics")
                            .optString("last_decision_event_id", ""),
                    )?.takeIf { trace ->
                        trace.optInt("reset_generation", -1) ==
                            state.getInt("probe_reset_generation") &&
                            trace.optString("decision_goal", correlationGoal) == correlationGoal &&
                            trace.optString("decision_target", correlationTarget) == correlationTarget
                    }
                } else {
                    null
                }
                val priorTraceFactIds = priorDecisionTrace
                    ?.optJSONArray("selected_context_ids")
                    ?.let(::strings)
                    .orEmpty()
                    .filter { it.startsWith("fact-") }
                val priorTraceDependenciesAreCurrent = priorTraceFactIds.isNotEmpty() &&
                    priorTraceFactIds.all { selectedId ->
                        state.getJSONObject("facts").optJSONObject(selectedId)?.let { fact ->
                            fact.optString("status", "ACTIVE") == "ACTIVE" &&
                                fact.optString("goal", "") == correlationGoal &&
                                fact.optString("entity", "") == correlationTarget
                        } == true
                    }
                val priorTraceDependencies = if (priorTraceDependenciesAreCurrent) {
                    priorTraceFactIds
                } else {
                    emptyList()
                }
                val correlatedPriorDecisionTrace = priorDecisionTrace
                    ?.takeIf { priorTraceDependencies.isNotEmpty() }
                val invalidatedPendingReplay = explicitOutcome != null &&
                    pendingSelection.id == null &&
                    if (correlationGoal.isNotEmpty() && correlationTarget.isNotEmpty()) {
                        isPendingOutcomeScopeInvalidated(
                            state = state,
                            goal = correlationGoal,
                            target = correlationTarget,
                        ) || strings(
                            state.getJSONArray("invalidated_pending_outcome_fingerprints"),
                        ).contains(
                            pendingOutcomeFingerprint(
                                correlationGoal,
                                correlationTarget,
                                explicitOutcome,
                            ),
                        )
                    } else {
                        strings(
                            state.getJSONArray(
                                "invalidated_pending_outcome_value_fingerprints",
                            ),
                        ).contains(Sha256.utf8(explicitOutcome))
                    }
                val missingExplicitLineage = explicitOutcome != null &&
                    pendingSelection.id == null &&
                    correlatedPriorDecisionTrace == null
                val outcome = if (
                    pendingSelection.ambiguous ||
                    invalidatedPendingReplay ||
                    missingExplicitLineage
                ) {
                    null
                } else {
                    explicitOutcome ?: pending?.optString("value", "")
                        ?.takeIf(String::isNotEmpty)
                }
                if (outcome != null) {
                    val outcomeId = "outcome-${safeId(step.eventId)}"
                    val outcomeGoal = pending?.optString("goal", "")?.takeIf(String::isNotEmpty)
                        ?: correlationGoal
                    val outcomeTarget = pending?.optString("entity", "")?.takeIf(String::isNotEmpty)
                        ?: correlationTarget
                    state.getJSONObject("delayed_outcomes").put(
                        outcomeId,
                        JSONObject()
                            .put("value", outcome)
                            .put("arrived_at", step.virtualTime)
                            .put("source_event_id", step.eventId)
                            .put("source_evidence_id", evidenceId)
                            .put(
                                "registered_by_event_id",
                                pending?.optString("registered_by_event_id", "")
                                    ?: correlatedPriorDecisionTrace?.optString("event_id", "")
                                    ?: "",
                            )
                            .put(
                                "registered_evidence_id",
                                pending?.optString("registered_evidence_id", "")
                                    ?: correlatedPriorDecisionTrace
                                        ?.optString("result_evidence_id", "")
                                    ?: "",
                            )
                            .put(
                                "registered_action_record_event_id",
                                correlatedPriorDecisionTrace
                                    ?.optString("action_record_event_id", "")
                                    ?: "",
                            )
                            .put(
                                "dependency_ids",
                                pending?.optJSONArray("dependency_ids")?.let(::copyArray)
                                    ?: JSONArray(priorTraceDependencies),
                            )
                            .put("review_state", "UNREVIEWED")
                            .put(
                                "goal",
                                outcomeGoal,
                            )
                            .put(
                                "entity",
                                outcomeTarget,
                            ),
                    )
                    appendUnique(state.getJSONArray("delayed_outcome_order"), outcomeId)
                    state.put("latest_outcome_id", outcomeId)
                    pendingSelection.id?.let { pendingId ->
                        state.getJSONObject("pending_delayed_outcomes").remove(pendingId)
                        removeString(state.getJSONArray("pending_delayed_outcome_order"), pendingId)
                        invalidated += pendingId
                    }
                    if (state.getString("network") == "DELAYED") state.put("network", "ONLINE")
                    preserved += outcomeId
                }
                decision = when {
                    pendingSelection.ambiguous -> "ASK"
                    invalidatedPendingReplay -> "ABSTAIN"
                    missingExplicitLineage -> "ABSTAIN"
                    outcome != null -> "PENDING"
                    else -> "NO_DECISION"
                }
            }

            "EXPORT_AND_END" -> {
                preserveDurableWorkState(state, preserved)
                latestOutcomeId(state, "", "")?.let(preserved::add)
            }
        }

        pruneInactiveRouteRules(state).forEach(invalidated::add)

        // A state identifier cannot truthfully be both retained and invalidated
        // by the same transition. This also protects future operation handlers
        // from publishing contradictory public evidence.
        preserved.removeAll(invalidated)

        state.put("session_id", step.sessionId)
        state.put("virtual_time", step.virtualTime)
        appendUnique(state.getJSONArray("receipts"), receiptId)
        appendUnique(state.getJSONArray("evidence"), evidenceId)
        store.save(state)

        return JSONObject()
            .put("step_id", step.stepId)
            .put("event_id", step.eventId)
            .put("operation", step.operation)
            .put("session_id", step.sessionId)
            .put("virtual_time", step.virtualTime)
            .put("process_epoch", state.getInt("process_epoch"))
            .put("network_state", state.getString("network"))
            .put("decision_state", decision)
            .put("selected_context_ids", JSONArray(selected.toList()))
            .put("invalidated_state_ids", JSONArray(invalidated.toList()))
            .put("preserved_state_ids", JSONArray(preserved.toList()))
            .put("state_before_sha256", before)
            .put("state_after_sha256", Sha256.canonicalJson(state))
            .put("action", action ?: JSONObject.NULL)
            .put("tombstone_ids", copyArray(tombstones))
            .put("receipt_ids", JSONArray().put(receiptId))
            // A step publishes exactly one newly-created evidence artifact.
            // Earlier causal evidence remains bound in decision_traces; adding
            // it here would make RunEvidenceArchive treat a prior file as a
            // second output of this step and reject the official run.
            .put("auto_check_evidence_ids", JSONArray().put(evidenceId))
    }

    fun evidenceIds(): List<String> {
        val state = normalized(store.load())
        return strings(state.getJSONArray("evidence")) + strings(state.getJSONArray("receipts"))
    }

    fun decisionRequestCount(): Int =
        normalized(store.load()).getInt("decision_request_count")

    private fun resetRunState(
        state: JSONObject,
        preserveProbeRunHistory: Boolean = false,
    ) {
        state.put(
            "probe_reset_generation",
            if (preserveProbeRunHistory) {
                state.getInt("probe_reset_generation") + 1
            } else {
                0
            },
        )
        clearObject(state.getJSONObject("facts"))
        clearObject(state.getJSONObject("actions"))
        clearObject(state.getJSONObject("drafts"))
        clearObject(state.getJSONObject("draft_parts"))
        clearObject(state.getJSONObject("route_rules"))
        clearObject(state.getJSONObject("share_envelopes"))
        clearObject(state.getJSONObject("processed_share_events"))
        clearObject(state.getJSONObject("delayed_outcomes"))
        clearObject(state.getJSONObject("pending_delayed_outcomes"))
        clearObject(state.getJSONObject("fact_deletion_rules"))
        clearObject(state.getJSONObject("revoked_scope_rules"))
        if (!preserveProbeRunHistory) clearObject(state.getJSONObject("decision_traces"))
        clearObject(state.getJSONObject("action_dependencies"))
        clearObject(state.getJSONObject("decision_action_bindings"))
        clearArray(state.getJSONArray("share_order"))
        clearArray(state.getJSONArray("action_order"))
        clearArray(state.getJSONArray("draft_order"))
        clearArray(state.getJSONArray("delayed_outcome_order"))
        clearArray(state.getJSONArray("pending_delayed_outcome_order"))
        clearArray(state.getJSONArray("invalidated_pending_outcome_fingerprints"))
        clearArray(state.getJSONArray("invalidated_pending_outcome_value_fingerprints"))
        clearArray(state.getJSONArray("invalidated_pending_outcome_scope_fingerprints"))
        clearArray(state.getJSONArray("tombstones"))
        clearArray(state.getJSONArray("tombstoned_fact_ids"))
        clearArray(state.getJSONArray("tombstoned_fact_fingerprints"))
        clearArray(state.getJSONArray("tombstoned_share_event_ids"))
        clearArray(state.getJSONArray("revoked_scopes"))
        clearArray(state.getJSONArray("revoked_targets"))
        clearArray(state.getJSONArray("revoked_scope_labels"))
        if (!preserveProbeRunHistory) {
            clearArray(state.getJSONArray("receipts"))
            clearArray(state.getJSONArray("evidence"))
        }
        clearArray(state.getJSONArray("product_episode_history"))
        state.remove("pending_route_change")
        state.remove("pending_context_change")
        state.remove("active_payload_descriptor")
        state.remove("last_candidate_evaluation")
        state.remove("last_recovery")
        state.put("network", "ONLINE")
        if (!preserveProbeRunHistory) state.put("decision_request_count", 0)
        state.put("latest_outcome_id", "")
        state.put("active_purpose", "")
        state.put("episode", "NOT_STARTED")
        state.put("external_dispatch_count", 0)
        state.put("product_session_counter", 0)
        state.put("product_session_id", "")
        state.put("last_decision_goal", "")
        state.put("last_decision_target", "")
        state.put("product_interaction_metrics", newProductInteractionMetrics())
        if (!preserveProbeRunHistory) {
            state.put("probe_interaction_metrics", newProbeInteractionMetrics())
            state.put("active_probe_run_id", "")
        }
        clearObject(state.getJSONObject("safe_share_context"))
    }

    private fun ensureConfirmedRouteFact(
        state: JSONObject,
        purpose: String,
        target: String,
        sourceEventId: String,
    ): String {
        val routeFactId = productRouteFactId(purpose, target)
        val facts = state.getJSONObject("facts")
        val existing = facts.optJSONObject(routeFactId)
        if (existing == null || existing.optString("status") != "ACTIVE") {
            putFact(
                facts = facts,
                factId = routeFactId,
                goal = purpose,
                target = target,
                kind = "STABLE",
                value = target,
                authority = "USER_CONFIRMED_ROUTE",
                scope = "LOCAL_ROUTE",
                sessionId = state.optString("product_session_id", ""),
                eventId = sourceEventId,
            )
        }
        return routeFactId
    }

    private fun supersedeConfirmedRouteFacts(
        state: JSONObject,
        purpose: String,
        oldTarget: String,
        replacementTarget: String,
    ) {
        val facts = state.getJSONObject("facts")
        facts.keys().asSequence().toList().forEach { factId ->
            val fact = facts.getJSONObject(factId)
            if (
                fact.optString("goal") == purpose &&
                fact.optString("entity") == oldTarget &&
                fact.optString("kind") == "STABLE" &&
                fact.optString("authority") == "USER_CONFIRMED_ROUTE" &&
                fact.optString("status", "ACTIVE") == "ACTIVE"
            ) {
                fact.put("status", "SUPERSEDED")
                    .put("superseded_by_target", replacementTarget)
            }
        }
    }

    private fun linkProductDraftAndAction(
        state: JSONObject,
        envelope: JSONObject,
        rawFactId: String,
        routeFactId: String,
        revisionToken: String? = null,
    ) {
        val eventId = envelope.getString("event_id")
        val purpose = envelope.getString("purpose")
        val target = envelope.getString("target")
        val dependencies = listOf(rawFactId, routeFactId)
        val draftSourceEventId = revisionToken
            ?.let { "$eventId-replan-${safeId(it)}" }
            ?: eventId
        val draftId = createDraft(
            state = state,
            sourceEventId = draftSourceEventId,
            purpose = purpose,
            target = target,
            dependencyIds = dependencies,
            contextBinding = contextBindingForDraft(state, purpose, target),
        )
        val actionEventId = productActionEventId(draftSourceEventId, target)
        proposalFor(actionEventId, state, draftId)
        state.getJSONObject("action_dependencies")
            .put(actionEventId, JSONArray(dependencies))
        envelope.put("draft_id", draftId)
            .put("action_event_id", actionEventId)
            .put("route_fact_id", routeFactId)
    }

    /**
     * Stores the minimum safety context that made a product draft actionable.
     * It is local metadata, not raw share content or an externally supplied
     * answer.  E3 uses it to select only drafts invalidated by the changed
     * context field; drafts from older, independent context stay untouched.
     */
    private fun contextBindingForDraft(
        state: JSONObject,
        purpose: String,
        target: String,
    ): JSONObject {
        val context = state.getJSONObject("safe_share_context")
        return JSONObject()
            .put("purpose", purpose)
            .put("target_room_id", target)
            .put("room_display", context.optString("room_display"))
            .put("current_role", context.optString("current_role"))
            .put("allowed_scope", context.optString("allowed_scope"))
            .put("link_access", context.optString("link_access").uppercase())
            .put("content_kind", context.optString("content_kind"))
    }

    /** A new LINK can reuse a room only after its own fingerprint is confirmed. */
    private fun payloadAllowsConfirmedRouteReuse(descriptor: JSONObject?): Boolean {
        if (descriptor?.optBoolean("requires_link_access_confirmation", false) != true) {
            return true
        }
        val contentSha256 = descriptor.optString("content_sha256")
        return contentSha256.isNotEmpty() &&
            SafeSharePolicy.linkAccessConfirmed(descriptor.optString("link_access")) &&
            descriptor.optString("link_access_for_sha256") == contentSha256
    }

    private fun draftDependsOnChangedContext(
        draftId: String,
        draft: JSONObject,
        changedFields: Set<String>,
        oldTarget: String,
        oldContext: JSONObject,
        activeDraftId: String,
        activeEventId: String,
        linkOnlyChange: Boolean,
    ): Boolean {
        val binding = draft.optJSONObject("context_binding")
        // A pre-binding draft has unknown lineage.  Preserve the previous
        // fail-closed selection so an upgrade never silently reuses it.
        if (binding == null) return draft.optString("target") == oldTarget
        if (linkOnlyChange) {
            return (activeDraftId.isNotEmpty() && draftId == activeDraftId) ||
                (activeDraftId.isEmpty() && draft.optString("source_event_id") == activeEventId)
        }
        if (
            "TARGET_ROOM" in changedFields &&
            binding.optString("target_room_id") == oldTarget
        ) return true
        if (
            "ROOM_PROFILE" in changedFields &&
            binding.optString("room_display") == oldContext.optString("room_display")
        ) return true
        if (
            "CURRENT_ROLE" in changedFields &&
            binding.optString("current_role") == oldContext.optString("current_role")
        ) return true
        if (
            "ALLOWED_SCOPE" in changedFields &&
            binding.optString("allowed_scope") == oldContext.optString("allowed_scope")
        ) return true
        return false
    }

    private fun memorySnapshot(state: JSONObject): JSONObject {
        val envelopes = state.getJSONObject("share_envelopes")
        val orderedIds = (
            strings(state.getJSONArray("share_order")) +
                envelopes.keys().asSequence().filterNot {
                    strings(state.getJSONArray("share_order")).contains(it)
                }.sorted()
            ).distinct()
        val shares = JSONArray()
        orderedIds.forEach { envelopeId ->
            envelopes.optJSONObject(envelopeId)?.let { envelope ->
                val publicEnvelope = JSONObject(CanonicalJson.encode(envelope))
                val privatePdfBound = publicEnvelope
                    .optString("private_pdf_copy_token", "")
                    .isNotEmpty()
                publicEnvelope.remove("private_pdf_copy_token")
                shares.put(
                    publicEnvelope
                        .put("private_pdf_copy_bound", privatePdfBound)
                        .put("share_id", envelopeId),
                )
            }
        }
        return JSONObject()
            .put("shares", shares)
            .put(
                "latest_share_id",
                if (shares.length() == 0) {
                    JSONObject.NULL
                } else {
                    shares.getJSONObject(shares.length() - 1).getString("share_id")
                },
            )
            .put("route_rules", JSONObject(CanonicalJson.encode(state.getJSONObject("route_rules"))))
            .put("facts", JSONObject(CanonicalJson.encode(state.getJSONObject("facts"))))
            .put("drafts", JSONObject(CanonicalJson.encode(state.getJSONObject("drafts"))))
            .put("actions", JSONObject(CanonicalJson.encode(state.getJSONObject("actions"))))
            .put(
                "decision_traces",
                JSONObject(CanonicalJson.encode(state.getJSONObject("decision_traces"))),
            )
            .put(
                "probe_interaction_metrics",
                JSONObject(CanonicalJson.encode(state.getJSONObject("probe_interaction_metrics"))),
            )
            .put(
                "action_dependencies",
                JSONObject(CanonicalJson.encode(state.getJSONObject("action_dependencies"))),
            )
            .put("tombstone_ids", copyArray(state.getJSONArray("tombstones")))
            .put(
                "deleted_share_event_ids",
                copyArray(state.getJSONArray("tombstoned_share_event_ids")),
            )
            .put("product", productSnapshot(state))
    }

    private fun resolveShareEnvelopeId(
        state: JSONObject,
        shareIdOrEventId: String?,
    ): String? {
        val envelopes = state.getJSONObject("share_envelopes")
        val requested = shareIdOrEventId?.trim().orEmpty()
        if (requested.isEmpty()) return latestEnvelopeId(state)
        if (envelopes.has(requested)) return requested
        val processedId = state.getJSONObject("processed_share_events").optString(requested, "")
        if (processedId.isNotEmpty() && envelopes.has(processedId)) return processedId
        return envelopes.keys().asSequence().firstOrNull { envelopeId ->
            envelopes.getJSONObject(envelopeId).optString("event_id") == requested
        }
    }

    private fun deleteShareEnvelopesLinkedToFact(
        state: JSONObject,
        factId: String,
        sourceEventId: String,
        reason: String,
    ): Set<String> {
        val envelopes = state.getJSONObject("share_envelopes")
        val linkedIds = envelopes.keys().asSequence().filter { envelopeId ->
            val envelope = envelopes.getJSONObject(envelopeId)
            envelope.optString("raw_fact_id") == factId ||
                (
                    sourceEventId.isNotEmpty() &&
                        envelope.optString("event_id") == sourceEventId
                    )
        }.toList()
        val invalidated = linkedSetOf<String>()
        linkedIds.forEach { envelopeId ->
            invalidated += deleteShareEnvelope(state, envelopeId, reason)
        }
        return invalidated
    }

    private fun deleteShareEnvelope(
        state: JSONObject,
        envelopeId: String,
        reason: String,
    ): Set<String> {
        val envelopes = state.getJSONObject("share_envelopes")
        val envelope = envelopes.optJSONObject(envelopeId) ?: return emptySet()
        val eventId = envelope.optString("event_id", "")
        val rawFactId = envelope.optString("raw_fact_id")
            .ifEmpty { productRawFactId(eventId) }
        val invalidated = linkedSetOf<String>()
        val facts = state.getJSONObject("facts")
        facts.optJSONObject(rawFactId)?.let { rawFact ->
            appendUnique(state.getJSONArray("tombstoned_fact_ids"), rawFactId)
            appendUnique(
                state.getJSONArray("tombstoned_fact_fingerprints"),
                factFingerprint(
                    rawFact.optString("goal", ""),
                    rawFact.optString("entity", envelopeId),
                    rawFact.optString("kind", "EPHEMERAL"),
                    rawFact.optString("value", ""),
                ),
            )
            facts.remove(rawFactId)
            appendUnique(state.getJSONArray("tombstones"), tombstoneId(rawFactId))
            invalidated += rawFactId
        }

        val cancelledDraftIds = linkedSetOf<String>()
        val drafts = state.getJSONObject("drafts")
        drafts.keys().asSequence().toList().forEach { draftId ->
            val draft = drafts.getJSONObject(draftId)
            if (
                draft.optString("source_event_id") == eventId ||
                draftId == envelope.optString("draft_id")
            ) {
                draft.put("commit_state", "CANCELLED")
                    .put("invalidated_by", reason)
                cancelDraftParts(state, draftId, reason)
                cancelledDraftIds += draftId
                invalidated += draftId
            }
        }
        cancelActionsForDrafts(state, cancelledDraftIds, reason).forEach(invalidated::add)
        cancelActionsDependingOn(state, setOf(rawFactId))

        envelopes.remove(envelopeId)
        removeString(state.getJSONArray("share_order"), envelopeId)
        if (eventId.isNotEmpty()) {
            state.getJSONObject("processed_share_events").remove(eventId)
            appendUnique(state.getJSONArray("tombstoned_share_event_ids"), eventId)
            appendUnique(state.getJSONArray("tombstones"), shareEventTombstoneId(eventId))
            if (
                state.optJSONObject("active_payload_descriptor")
                    ?.optString("event_id") == eventId
            ) {
                state.remove("active_payload_descriptor")
            }
        }
        invalidated += envelopeId
        return invalidated
    }

    private fun cancelActionsForDrafts(
        state: JSONObject,
        draftIds: Set<String>,
        reason: String,
    ): Set<String> {
        if (draftIds.isEmpty()) return emptySet()
        val cancelled = linkedSetOf<String>()
        val actions = state.getJSONObject("actions")
        actions.keys().asSequence().toList().forEach { eventId ->
            val action = actions.getJSONObject(eventId)
            if (
                action.optString("draft_id") in draftIds &&
                action.optString("commit_state") != "CANCELLED"
            ) {
                action.put("commit_state", "CANCELLED")
                    .put("outcome_id", JSONObject.NULL)
                    .put("invalidated_by", reason)
                cancelled += eventId
            }
        }
        return cancelled
    }

    private fun createDraft(
        state: JSONObject,
        sourceEventId: String,
        purpose: String,
        target: String,
        preservedScope: String = "LOCAL_ROUTE",
        dependencyIds: List<String> = emptyList(),
        contextBinding: JSONObject? = null,
    ): String {
        val draftId = "draft-${safeId(sourceEventId)}-${safeId(target)}"
        val drafts = state.getJSONObject("drafts")
        if (!drafts.has(draftId)) {
            drafts.put(
                draftId,
                JSONObject()
                    .put("source_event_id", sourceEventId)
                    .put("purpose", purpose)
                    .put("target", target)
                    .put("commit_state", "PROPOSED")
                    .put("external_dispatch", false),
            )
            contextBinding?.let { binding ->
                drafts.getJSONObject(draftId).put(
                    "context_binding",
                    JSONObject(CanonicalJson.encode(binding)),
                )
            }
            appendUnique(state.getJSONArray("draft_order"), draftId)
        }
        ensureDraftParts(
            state = state,
            draftId = draftId,
            purpose = purpose,
            target = target,
            preservedScope = preservedScope,
            dependencyIds = dependencyIds,
        )
        return draftId
    }

    private fun ensureDraftParts(
        state: JSONObject,
        draftId: String,
        purpose: String,
        target: String,
        preservedScope: String,
        dependencyIds: List<String>,
    ) {
        val parts = state.getJSONObject("draft_parts")
        val mainPartId = "draft-part-${safeId(draftId)}-route"
        val optionalPartId = "draft-part-${safeId(draftId)}-optional"
        if (!parts.has(mainPartId)) {
            parts.put(
                mainPartId,
                JSONObject()
                    .put("draft_id", draftId)
                    .put("purpose", purpose)
                    .put("target", target)
                    .put("kind", "ROUTE_BINDING")
                    .put("scope_role", "PRESERVED")
                    .put("scope_label", preservedScope)
                    .put("dependency_ids", JSONArray(dependencyIds))
                    .put("status", "ACTIVE"),
            )
        }
        if (!parts.has(optionalPartId)) {
            parts.put(
                optionalPartId,
                JSONObject()
                    .put("draft_id", draftId)
                    .put("purpose", purpose)
                    .put("target", target)
                    .put("kind", "OPTIONAL_LOCAL_CONTEXT")
                    .put("scope_role", "REVOCABLE")
                    .put("scope_label", "")
                    .put("preserved_parent_scope", preservedScope)
                    .put("dependency_ids", JSONArray(dependencyIds))
                    .put("status", "ACTIVE"),
            )
        }
        state.getJSONObject("drafts").getJSONObject(draftId).put(
            "component_ids",
            JSONArray().put(mainPartId).put(optionalPartId),
        )
    }

    private fun cancelDraftParts(state: JSONObject, draftId: String, reason: String): Set<String> {
        val parts = state.getJSONObject("draft_parts")
        val cancelled = linkedSetOf<String>()
        parts.keys().asSequence().toList().forEach { partId ->
            val part = parts.getJSONObject(partId)
            if (
                part.optString("draft_id") == draftId &&
                part.optString("status") != "REVOKED"
            ) {
                part.put("status", "CANCELLED")
                    .put("invalidated_by", reason)
                cancelled += partId
            }
        }
        return cancelled
    }

    private fun revocationCandidates(
        state: JSONObject,
        facts: JSONObject,
        purpose: String,
        target: String,
        revokedScope: String,
        preservedScope: String,
    ): List<String> {
        val exactFacts = facts.keys().asSequence()
            .filter { factId ->
                val fact = facts.getJSONObject(factId)
                    fact.optString("status", "ACTIVE") == "ACTIVE" &&
                    fact.optString("scope") == revokedScope &&
                    fact.optString("goal") == purpose &&
                    fact.optString("entity") == target
            }
            .map { "$FACT_CANDIDATE_PREFIX$it" }
            .toList()
        val parts = state.getJSONObject("draft_parts")
        val drafts = state.getJSONObject("drafts")
        val exactParts = parts.keys().asSequence()
            .filter { partId ->
                val part = parts.getJSONObject(partId)
                val draft = drafts.optJSONObject(part.optString("draft_id"))
                part.optString("status") == "ACTIVE" &&
                    part.optString("scope_role") == "REVOCABLE" &&
                    part.optString("scope_label") == revokedScope &&
                    draft?.optString("commit_state") == "PROPOSED" &&
                    part.optString("purpose") == purpose &&
                    part.optString("target") == target
            }
            .map { "$PART_CANDIDATE_PREFIX$it" }
            .toList()
        val exact = (exactFacts + exactParts).sorted()
        if (exact.isNotEmpty()) return exact

        return parts.keys().asSequence()
            .filter { partId ->
                val part = parts.getJSONObject(partId)
                val draft = drafts.optJSONObject(part.optString("draft_id"))
                part.optString("status") == "ACTIVE" &&
                    part.optString("scope_role") == "REVOCABLE" &&
                    part.optString("scope_label").isEmpty() &&
                    (
                        preservedScope.isEmpty() ||
                            part.optString("preserved_parent_scope") == preservedScope
                        ) &&
                    draft?.optString("commit_state") == "PROPOSED" &&
                    part.optString("purpose") == purpose &&
                    part.optString("target") == target
            }
            .map { "$PART_CANDIDATE_PREFIX$it" }
            .sorted()
            .toList()
    }

    private fun isExactRevocationCandidate(
        state: JSONObject,
        facts: JSONObject,
        candidate: String,
        revokedScope: String,
    ): Boolean = when {
        candidate.startsWith(FACT_CANDIDATE_PREFIX) -> {
            val factId = candidate.removePrefix(FACT_CANDIDATE_PREFIX)
            facts.optJSONObject(factId)?.optString("scope") == revokedScope
        }
        candidate.startsWith(PART_CANDIDATE_PREFIX) -> {
            val partId = candidate.removePrefix(PART_CANDIDATE_PREFIX)
            state.getJSONObject("draft_parts")
                .optJSONObject(partId)
                ?.optString("scope_label") == revokedScope
        }
        else -> false
    }

    private fun preserveCurrentScopeState(
        state: JSONObject,
        facts: JSONObject,
        purpose: String,
        target: String,
        preservedScope: String,
        preserved: MutableSet<String>,
    ) {
        facts.keys().asSequence().toList().forEach { factId ->
            val fact = facts.getJSONObject(factId)
            if (
                fact.optString("status", "ACTIVE") == "ACTIVE" &&
                (
                    (preservedScope.isNotEmpty() && fact.optString("scope") == preservedScope) ||
                        (
                            preservedScope.isEmpty() &&
                                fact.optString("goal") == purpose
                            )
                    ) &&
                fact.optString("goal") == purpose &&
                fact.optString("entity") == target
            ) {
                preserved += factId
            }
        }

        val parts = state.getJSONObject("draft_parts")
        parts.keys().asSequence().toList().forEach { partId ->
            val part = parts.getJSONObject(partId)
            if (
                part.optString("scope_role") == "PRESERVED" &&
                part.optString("status") in setOf("ACTIVE", "COMMITTED") &&
                (
                    preservedScope.isEmpty() ||
                        part.optString("scope_label") == preservedScope
                    ) &&
                part.optString("purpose") == purpose &&
                part.optString("target") == target
            ) {
                preserved += partId
                part.optString("draft_id", "").takeIf(String::isNotEmpty)?.let(preserved::add)
            }
        }
    }

    private fun markDraftForScopedReplan(
        state: JSONObject,
        revokedPart: JSONObject,
        revokedPartId: String,
    ) {
        val draftId = revokedPart.optString("draft_id", "")
        if (draftId.isEmpty()) return
        state.getJSONObject("drafts").optJSONObject(draftId)?.let { draft ->
            draft.put("replan_required", true)
            val invalidatedParts = draft.optJSONArray("invalidated_part_ids")
                ?: JSONArray().also { draft.put("invalidated_part_ids", it) }
            appendUnique(invalidatedParts, revokedPartId)
        }
        val actions = state.getJSONObject("actions")
        actions.keys().asSequence().toList().forEach { eventId ->
            val action = actions.getJSONObject(eventId)
            if (
                action.optString("draft_id") == draftId &&
                action.optString("commit_state") == "PROPOSED"
            ) {
                action.put("replan_required", true)
                val invalidatedParts = action.optJSONArray("invalidated_part_ids")
                    ?: JSONArray().also { action.put("invalidated_part_ids", it) }
                appendUnique(invalidatedParts, revokedPartId)
            }
        }
    }

    private fun cancelDraftsForTarget(state: JSONObject, target: String, reason: String) {
        val drafts = state.getJSONObject("drafts")
        val cancelledDraftIds = linkedSetOf<String>()
        drafts.keys().asSequence().toList().forEach { draftId ->
            val draft = drafts.getJSONObject(draftId)
            if (draft.optString("target") == target) {
                draft.put("commit_state", "CANCELLED")
                draft.put("invalidated_by", reason)
                cancelDraftParts(state, draftId, reason)
                cancelledDraftIds += draftId
            }
        }
        cancelActionsForDrafts(state, cancelledDraftIds, reason)
    }

    private fun cancelDraftsForPurposeAndTarget(
        state: JSONObject,
        purpose: String,
        target: String,
        reason: String,
    ) {
        val drafts = state.getJSONObject("drafts")
        val cancelledDraftIds = linkedSetOf<String>()
        drafts.keys().asSequence().toList().forEach { draftId ->
            val draft = drafts.getJSONObject(draftId)
            if (
                draft.optString("purpose") == purpose &&
                draft.optString("target") == target
            ) {
                draft.put("commit_state", "CANCELLED")
                draft.put("invalidated_by", reason)
                cancelDraftParts(state, draftId, reason)
                cancelledDraftIds += draftId
            }
        }
        cancelActionsForDrafts(state, cancelledDraftIds, reason)
    }

    private fun actionFor(eventId: String, state: JSONObject): JSONObject {
        val actions = state.getJSONObject("actions")
        val existing = actions.optJSONObject(eventId)
        if (existing != null) return publicAction(existing)
        val suffix = safeId(eventId)
        return JSONObject()
            .put("action_id", "local-draft-$suffix")
            .put("idempotency_key", "idem-$suffix")
            .put("commit_state", "COMMITTED")
            .put("outcome_id", "local-outcome-$suffix")
            .also {
                actions.put(eventId, JSONObject(CanonicalJson.encode(it)))
                appendUnique(state.getJSONArray("action_order"), eventId)
            }
    }

    private fun proposalFor(eventId: String, state: JSONObject, draftId: String): JSONObject {
        val actions = state.getJSONObject("actions")
        val existing = actions.optJSONObject(eventId)
        if (existing != null) return publicAction(existing)
        val suffix = safeId(eventId)
        val stored = JSONObject()
            .put("action_id", "local-draft-$suffix")
            .put("idempotency_key", "idem-$suffix")
            .put("commit_state", "PROPOSED")
            .put("outcome_id", JSONObject.NULL)
            .put("draft_id", draftId)
        actions.put(eventId, stored)
        appendUnique(state.getJSONArray("action_order"), eventId)
        return publicAction(stored)
    }

    private fun publicAction(record: JSONObject): JSONObject =
        JSONObject()
            .put("action_id", record.opt("action_id") ?: JSONObject.NULL)
            .put("idempotency_key", record.opt("idempotency_key") ?: JSONObject.NULL)
            .put("commit_state", record.optString("commit_state", "NONE"))
            .put("outcome_id", record.opt("outcome_id") ?: JSONObject.NULL)

    private fun commitNextProposedAction(
        state: JSONObject,
        selectedIds: Set<String>,
        reconciledByEventId: String,
    ): Pair<String, JSONObject>? {
        val actions = state.getJSONObject("actions")
        val dependencies = state.getJSONObject("action_dependencies")
        val orderedEventIds = (
            strings(state.getJSONArray("action_order")) +
                actions.keys().asSequence().filterNot {
                    strings(state.getJSONArray("action_order")).contains(it)
                }.sorted()
            ).distinct()
        orderedEventIds.forEach { eventId ->
            val record = actions.getJSONObject(eventId)
            if (record.optString("commit_state") != "PROPOSED") return@forEach
            val required = dependencies.optJSONArray(eventId)
                ?.let(::strings)
                .orEmpty()
            if (required.isEmpty() || required.all(selectedIds::contains)) {
                record.put("commit_state", "COMMITTED")
                record.put("outcome_id", "local-outcome-${safeId(eventId)}")
                record.put("reconciled_by_event", reconciledByEventId)
                record.put("replan_required", false)
                val draftId = record.optString("draft_id", "")
                if (draftId.isNotEmpty()) {
                    state.getJSONObject("drafts").optJSONObject(draftId)?.let { draft ->
                        draft.put("commit_state", "COMMITTED")
                            .put("replan_required", false)
                            .put("reconciled_by_event", reconciledByEventId)
                    }
                    val parts = state.getJSONObject("draft_parts")
                    parts.keys().asSequence().toList().forEach { partId ->
                        val part = parts.getJSONObject(partId)
                        if (
                            part.optString("draft_id") == draftId &&
                            part.optString("status") == "ACTIVE"
                        ) {
                            part.put("status", "COMMITTED")
                        }
                    }
                }
                return eventId to record
            }
        }
        return null
    }

    private fun cancelActionsDependingOn(
        state: JSONObject,
        invalidatedIds: Set<String>,
        reason: String = "dependency-invalidated",
    ): Set<String> {
        if (invalidatedIds.isEmpty()) return emptySet()
        val cancelled = linkedSetOf<String>()
        val actions = state.getJSONObject("actions")
        val dependencies = state.getJSONObject("action_dependencies")
        actions.keys().asSequence().toList().forEach { eventId ->
            val record = actions.getJSONObject(eventId)
            if (record.optString("commit_state") != "PROPOSED") return@forEach
            val required = dependencies.optJSONArray(eventId)
                ?.let(::strings)
                .orEmpty()
            if (required.any(invalidatedIds::contains)) {
                record.put("commit_state", "CANCELLED")
                    .put("outcome_id", JSONObject.NULL)
                    .put("invalidated_by", reason)
                record.optString("action_id", "")
                    .takeIf(String::isNotEmpty)
                    ?.let(cancelled::add)
                val draftId = record.optString("draft_id", "")
                if (draftId.isNotEmpty()) {
                    state.getJSONObject("drafts").optJSONObject(draftId)?.let { draft ->
                        if (draft.optString("commit_state") != "CANCELLED") {
                            draft.put("commit_state", "CANCELLED")
                                .put("invalidated_by", reason)
                            cancelled += draftId
                            cancelled += cancelDraftParts(state, draftId, reason)
                        }
                    }
                }
            }
        }
        return cancelled
    }

    private fun resolveActionRecordEventId(state: JSONObject, replayedEventId: String): String? {
        val actions = state.getJSONObject("actions")
        if (actions.has(replayedEventId)) return replayedEventId
        state.getJSONObject("decision_action_bindings")
            .optString(replayedEventId, "")
            .takeIf(actions::has)
            ?.let { return it }
        return state.getJSONObject("decision_traces")
            .optJSONObject(replayedEventId)
            ?.optString("action_record_event_id", "")
            ?.takeIf(actions::has)
    }

    private fun preserveDurableWorkState(
        state: JSONObject,
        preserved: MutableSet<String>,
    ) {
        currentFactIds(state.getJSONObject("facts"), "", "").forEach(preserved::add)
        val drafts = state.getJSONObject("drafts")
        drafts.keys().asSequence().sorted().forEach { draftId ->
            if (drafts.getJSONObject(draftId).optString("commit_state") != "CANCELLED") {
                preserved += draftId
            }
        }
        val actions = state.getJSONObject("actions")
        actions.keys().asSequence().sorted().forEach { eventId ->
            val action = actions.getJSONObject(eventId)
            if (action.optString("commit_state") != "CANCELLED") {
                action.optString("action_id", "")
                    .takeIf(String::isNotEmpty)
                    ?.let(preserved::add)
            }
        }
        state.getJSONObject("pending_delayed_outcomes").keys().asSequence()
            .sorted()
            .forEach(preserved::add)
        state.getJSONObject("delayed_outcomes").keys().asSequence()
            .filter { outcomeId ->
                state.getJSONObject("delayed_outcomes")
                    .getJSONObject(outcomeId)
                    .optString("status", "ACTIVE") != "INVALIDATED"
            }
            .sorted()
            .forEach(preserved::add)
    }

    private fun latestLifecycleDecision(state: JSONObject): String {
        val actions = state.getJSONObject("actions")
        val actionOrder = (
            strings(state.getJSONArray("action_order")) +
                actions.keys().asSequence().filterNot {
                    strings(state.getJSONArray("action_order")).contains(it)
                }.sorted()
            ).distinct()
        val orderedActions = actionOrder.asReversed().mapNotNull(actions::optJSONObject)
        if (orderedActions.any { it.optString("commit_state") == "PROPOSED" }) return "PENDING"

        val drafts = state.getJSONObject("drafts")
        val draftOrder = (
            strings(state.getJSONArray("draft_order")) +
                drafts.keys().asSequence().filterNot {
                    strings(state.getJSONArray("draft_order")).contains(it)
                }.sorted()
            ).distinct()
        val orderedDrafts = draftOrder.asReversed().mapNotNull(drafts::optJSONObject)
        if (
            orderedDrafts.any {
                it.optString("commit_state") == "PROPOSED" ||
                    it.optString("hold_state") == "HELD"
            }
        ) {
            return "PENDING"
        }
        val latestActionState = orderedActions.firstNotNullOfOrNull { action ->
            action.optString("commit_state", "").takeIf {
                it == "COMMITTED" || it == "CANCELLED"
            }
        }
        if (latestActionState != null) {
            return if (latestActionState == "COMMITTED") "CONFIRMED_COMPLETE" else "ABSTAIN"
        }
        val latestDraftState = orderedDrafts.firstNotNullOfOrNull { draft ->
            draft.optString("commit_state", "").takeIf {
                it == "COMMITTED" || it == "CANCELLED"
            }
        }
        return when (latestDraftState) {
            "COMMITTED" -> "CONFIRMED_COMPLETE"
            "CANCELLED" -> "ABSTAIN"
            else -> "NO_DECISION"
        }
    }

    private fun currentFactIds(
        facts: JSONObject,
        goal: String,
        target: String,
    ): List<String> = facts.keys().asSequence()
        .filter { factId ->
            val fact = facts.getJSONObject(factId)
            fact.optString("status", "ACTIVE") == "ACTIVE" &&
                (goal.isEmpty() || fact.optString("goal") == goal) &&
                (target.isEmpty() || fact.optString("entity") == target)
        }
        .sorted()
        .toList()

    private fun hasActiveRoutingFact(
        state: JSONObject,
        facts: JSONObject,
        goal: String,
        target: String,
    ): Boolean = facts.keys().asSequence().any { factId ->
        val fact = facts.getJSONObject(factId)
        fact.optString("status", "ACTIVE") == "ACTIVE" &&
            fact.optString("kind", "") == "STABLE" &&
            fact.optString("goal", "") == goal &&
            fact.optString("entity", "") == target &&
            !isScopeRevoked(state, goal, target, fact.optString("scope", ""))
    }

    private fun pruneInactiveRouteRules(state: JSONObject): Set<String> {
        val rules = state.getJSONObject("route_rules")
        val facts = state.getJSONObject("facts")
        val removed = linkedSetOf<String>()
        rules.keys().asSequence().toList().forEach { goal ->
            val target = rules.optString(goal, "")
            if (
                target.isEmpty() ||
                !hasActiveRoutingFact(state, facts, goal, target)
            ) {
                rules.remove(goal)
                removed += "route-rule-${safeId(goal)}-${safeId(target)}"
            }
        }
        return removed
    }

    private fun resolveGoalForTarget(
        state: JSONObject,
        facts: JSONObject,
        requestedGoal: String?,
        activeGoal: String,
        target: String,
    ): String? {
        requestedGoal?.let { return it }
        if (target == "default") return null
        val targetGoals = factIdsForTarget(facts, target, activeOnly = false)
            .map { facts.getJSONObject(it).optString("goal", "") }
            .filter(String::isNotEmpty)
            .distinct()
        val activeMatchesTarget = activeGoal.isNotEmpty() &&
            (
                state.getJSONObject("route_rules").optString(activeGoal, "") == target ||
                    activeGoal in targetGoals
                )
        return when {
            activeMatchesTarget -> activeGoal
            targetGoals.size == 1 -> targetGoals.single()
            else -> null
        }
    }

    private fun actionableFactIds(
        state: JSONObject,
        facts: JSONObject,
        goal: String,
        target: String,
    ): List<String> = currentFactIds(facts, goal, target).filter { factId ->
        val fact = facts.getJSONObject(factId)
        fact.optString("kind", "STABLE") != "DISTRACTOR" &&
            !isScopeRevoked(
                state,
                fact.optString("goal", ""),
                fact.optString("entity", ""),
                fact.optString("scope", ""),
            )
    }

    private fun factIdsForTarget(
        facts: JSONObject,
        target: String,
        activeOnly: Boolean,
    ): List<String> = facts.keys().asSequence()
        .filter { factId ->
            val fact = facts.getJSONObject(factId)
            fact.optString("entity") == target &&
                (!activeOnly || fact.optString("status", "ACTIVE") == "ACTIVE")
        }
        .sorted()
        .toList()

    private fun factValues(roles: JSONObject): List<Pair<String, String>> =
        buildList {
            role(roles, "STABLE_VALUE")?.let { add("STABLE" to it) }
            role(roles, "ONE_OFF_VALUE")?.let { add("ONE_OFF" to it) }
            role(roles, "EPHEMERAL_VALUE")?.let { add("EPHEMERAL" to it) }
        }

    private fun correctionValue(
        roles: JSONObject,
        target: String,
        existingFactIds: List<String>,
        facts: JSONObject,
    ): Pair<String, String> {
        role(roles, "STABLE_VALUE")?.let { return "STABLE" to it }
        role(roles, "ONE_OFF_VALUE")?.let { return "ONE_OFF" to it }
        role(roles, "EPHEMERAL_VALUE")?.let { return "EPHEMERAL" to it }
        val previousKind = existingFactIds.asReversed().asSequence()
            .map { facts.getJSONObject(it).optString("kind", "STABLE") }
            .firstOrNull()
            ?: "STABLE"
        return previousKind to target
    }

    private fun putFact(
        facts: JSONObject,
        factId: String,
        goal: String,
        target: String,
        kind: String,
        value: String,
        authority: String,
        scope: String,
        sessionId: String,
        eventId: String,
    ) {
        facts.put(
            factId,
            JSONObject()
                .put("goal", goal)
                .put("entity", target)
                .put("kind", kind)
                .put("value", value)
                .put("lifetime", kind)
                .put("authority", authority)
                .put("scope", scope)
                .put("session_id", sessionId)
                .put("source_event_id", eventId)
                .put("status", "ACTIVE"),
        )
    }

    private fun deletionFactIds(
        facts: JSONObject,
        target: String,
        goal: String,
        roles: JSONObject,
    ): List<String> {
        val requestedValues = requestedDeletionValues(roles)
        return facts.keys().asSequence().filter { factId ->
            val fact = facts.getJSONObject(factId)
            fact.optString("entity") == target &&
                (goal.isEmpty() || fact.optString("goal") == goal) &&
                if (requestedValues.isEmpty()) {
                    fact.optString("kind") in setOf("EPHEMERAL", "ONE_OFF")
                } else {
                    requestedValues.any { (kind, value) ->
                        fact.optString("kind") == kind && fact.optString("value") == value
                    }
                }
        }.sorted().toList()
    }

    private fun requestedDeletionValues(roles: JSONObject): List<Pair<String, String>> =
        buildList {
            role(roles, "STABLE_VALUE")?.let { add("STABLE" to it) }
            role(roles, "EPHEMERAL_VALUE")?.let { add("EPHEMERAL" to it) }
            role(roles, "ONE_OFF_VALUE")?.let { add("ONE_OFF" to it) }
        }

    private fun recordFactDeletionRules(
        state: JSONObject,
        goal: String,
        target: String,
        roles: JSONObject,
    ): Set<String> {
        val requested = requestedDeletionValues(roles)
        val rules = if (requested.isEmpty()) {
            listOf("EPHEMERAL" to null, "ONE_OFF" to null)
        } else {
            requested.map { (kind, value) -> kind to value }
        }
        val ledger = state.getJSONObject("fact_deletion_rules")
        return rules.mapTo(linkedSetOf()) { (kind, value) ->
            val descriptor = JSONObject()
                .put("goal", goal)
                .put("entity", target)
                .put("kind", kind)
                .put("value_fingerprint", value?.let(Sha256::utf8) ?: JSONObject.NULL)
                .put("value_wildcard", value == null)
            val ruleId = "delete-rule-${Sha256.canonicalJson(descriptor).take(24)}"
            ledger.put(ruleId, descriptor)
            appendUnique(state.getJSONArray("tombstones"), tombstoneId(ruleId))
            ruleId
        }
    }

    private fun isFactTombstoned(
        state: JSONObject,
        factId: String,
        goal: String,
        target: String,
        kind: String,
        value: String,
    ): Boolean {
        if (strings(state.getJSONArray("tombstoned_fact_ids")).contains(factId)) return true
        if (
            strings(state.getJSONArray("tombstoned_fact_fingerprints"))
                .contains(factFingerprint(goal, target, kind, value))
        ) {
            return true
        }
        val rules = state.getJSONObject("fact_deletion_rules")
        return rules.keys().asSequence().any { ruleId ->
            val rule = rules.getJSONObject(ruleId)
            (rule.optString("goal").isEmpty() || rule.optString("goal") == goal) &&
                rule.optString("entity") == target &&
                rule.optString("kind") == kind &&
                (
                    rule.optBoolean("value_wildcard") ||
                        rule.optString("value_fingerprint") == Sha256.utf8(value)
                    )
        }
    }

    private fun factFingerprint(
        goal: String,
        target: String,
        kind: String,
        value: String,
    ): String = Sha256.utf8(
        CanonicalJson.encode(JSONArray().put(goal).put(target).put(kind).put(value)),
    )

    private fun tombstoneId(factId: String): String = "tombstone-${safeId(factId)}"

    private fun requestedNetwork(roles: JSONObject): String {
        val allowed = setOf("ONLINE", "OFFLINE", "DELAYED", "UNKNOWN")
        fun allowedValue(value: Any?): String? = (value as? String)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it in allowed }

        if (roles.has("NETWORK_STATE")) {
            return allowedValue(roles.opt("NETWORK_STATE")) ?: "UNKNOWN"
        }
        if (roles.has("ONE_OFF_VALUE")) {
            return allowedValue(roles.opt("ONE_OFF_VALUE")) ?: "UNKNOWN"
        }
        return "UNKNOWN"
    }

    private fun shareObservation(
        state: JSONObject,
        envelopeId: String,
        duplicate: Boolean,
    ): JSONObject {
        val envelope = state.getJSONObject("share_envelopes").getJSONObject(envelopeId)
        return JSONObject()
            .put("event_id", envelope.getString("event_id"))
            .put("envelope_id", envelopeId)
            .put("status", envelope.getString("status"))
            .put("duplicate", duplicate)
            .put("route_reused", envelope.optBoolean("route_reused", false))
            .put(
                "route_confirmation_required_count",
                envelope.optInt(
                    "route_confirmation_required_count",
                    if (envelope.optString("status") == "ASK") 1 else 0,
                ),
            )
            .put("draft_id", envelope.opt("draft_id") ?: JSONObject.NULL)
    }

    private fun productSnapshot(state: JSONObject): JSONObject {
        val drafts = state.getJSONObject("drafts")
        val activeDrafts = drafts.keys().asSequence().count {
            drafts.getJSONObject(it).optString("commit_state") != "CANCELLED"
        }
        val heldDrafts = drafts.keys().asSequence().count {
            val draft = drafts.getJSONObject(it)
            draft.optString("commit_state") != "CANCELLED" &&
                draft.optString("hold_state") == "HELD"
        }
        val cancelledDrafts = drafts.length() - activeDrafts
        return JSONObject()
            .put("active_purpose", state.optString("active_purpose", ""))
            .put("route_rules", JSONObject(CanonicalJson.encode(state.getJSONObject("route_rules"))))
            .put("share_count", state.getJSONObject("share_envelopes").length())
            .put("active_draft_count", activeDrafts)
            .put("held_draft_count", heldDrafts)
            .put("cancelled_draft_count", cancelledDrafts)
            .put("revoked_scopes", copyArray(state.getJSONArray("revoked_scopes")))
            .put("revoked_targets", copyArray(state.getJSONArray("revoked_targets")))
            .put(
                "revoked_scope_labels",
                copyArray(state.getJSONArray("revoked_scope_labels")),
            )
            .put(
                "pending_route_change",
                state.optJSONObject("pending_route_change")?.let {
                    JSONObject(CanonicalJson.encode(it))
                } ?: JSONObject.NULL,
            )
            .put(
                "pending_context_change",
                state.optJSONObject("pending_context_change")?.let {
                    JSONObject(CanonicalJson.encode(it))
                } ?: JSONObject.NULL,
            )
            .put(
                "decision_state",
                if (
                    state.optJSONObject("pending_route_change") == null &&
                    state.optJSONObject("pending_context_change") == null
                ) {
                    "NO_DECISION"
                } else {
                    "ASK"
                },
            )
            .put("episode", state.optString("episode", "NOT_STARTED"))
            .put(
                "interaction_metrics",
                JSONObject(CanonicalJson.encode(state.getJSONObject("product_interaction_metrics"))),
            )
            .put(
                "safe_share_context",
                JSONObject(CanonicalJson.encode(state.getJSONObject("safe_share_context"))),
            )
            .put(
                "active_payload_descriptor",
                state.optJSONObject("active_payload_descriptor")?.let {
                    JSONObject(CanonicalJson.encode(it)).apply {
                        val privatePdfBound = optString("private_pdf_copy_token", "").isNotEmpty()
                        remove("private_pdf_copy_token")
                        put("private_pdf_copy_bound", privatePdfBound)
                    }
                } ?: JSONObject.NULL,
            )
            .put(
                "candidate_evaluation",
                state.optJSONObject("last_candidate_evaluation")?.let {
                    JSONObject(CanonicalJson.encode(it))
                } ?: JSONObject.NULL,
            )
            .put(
                "last_recovery",
                state.optJSONObject("last_recovery")?.let {
                    JSONObject(CanonicalJson.encode(it))
                } ?: JSONObject.NULL,
            )
            .put("process_epoch", state.getInt("process_epoch"))
            .put("product_session_id", state.optString("product_session_id", ""))
            .put("product_session_count", state.getInt("product_session_counter"))
            .put(
                "episode_history",
                copyArray(state.getJSONArray("product_episode_history")),
            )
            .put("external_dispatch_count", 0)
    }

    private fun beginProductSession(state: JSONObject) {
        val next = state.getInt("product_session_counter") + 1
        state.put("product_session_counter", next)
        state.put("product_session_id", "product-session-$next")
    }

    private fun recordProductEpisode(state: JSONObject, episode: String) {
        if (state.optString("product_session_id").isEmpty()) beginProductSession(state)
        state.put("episode", episode)
        val history = state.getJSONArray("product_episode_history")
        history.put(
            JSONObject()
                .put("sequence", history.length() + 1)
                .put("session_id", state.getString("product_session_id"))
                .put("episode", episode),
        )
    }

    private fun recordProductInteraction(
        state: JSONObject,
        eventId: String,
        routeReused: Boolean,
        routeConfirmationRequiredCount: Int,
    ) {
        val metrics = state.getJSONObject("product_interaction_metrics")
        metrics.put("share_event_count", metrics.getInt("share_event_count") + 1)
            .put(
                "route_reused_event_count",
                metrics.getInt("route_reused_event_count") + if (routeReused) 1 else 0,
            )
            .put(
                "route_confirmation_required_count",
                metrics.getInt("route_confirmation_required_count") +
                    routeConfirmationRequiredCount,
            )
            .put("last_share_event_id", eventId)
            .put("last_route_reused", routeReused)
            .put(
                "last_route_confirmation_required_count",
                routeConfirmationRequiredCount,
            )
    }

    private fun newProductInteractionMetrics(): JSONObject = JSONObject()
        .put("share_event_count", 0)
        .put("route_reused_event_count", 0)
        .put("route_confirmation_required_count", 0)
        .put("last_share_event_id", "")
        .put("last_route_reused", false)
        .put("last_route_confirmation_required_count", 0)

    private fun normalizeProductInteractionMetrics(metrics: JSONObject) {
        if (!metrics.has("share_event_count")) metrics.put("share_event_count", 0)
        if (!metrics.has("route_reused_event_count")) metrics.put("route_reused_event_count", 0)
        if (!metrics.has("route_confirmation_required_count")) {
            metrics.put("route_confirmation_required_count", 0)
        }
        if (!metrics.has("last_share_event_id")) metrics.put("last_share_event_id", "")
        if (!metrics.has("last_route_reused")) metrics.put("last_route_reused", false)
        if (!metrics.has("last_route_confirmation_required_count")) {
            metrics.put("last_route_confirmation_required_count", 0)
        }
    }

    private fun newProbeInteractionMetrics(): JSONObject = JSONObject()
        .put("decision_event_count", 0)
        .put("selected_context_count_total", 0)
        .put("reused_context_count_total", 0)
        .put("required_confirmation_count_total", 0)
        .put("last_decision_event_id", "")
        .put("last_selected_context_count", 0)
        .put("last_reused_context_count", 0)
        .put("last_required_confirmation_count", 0)

    private fun normalizeProbeInteractionMetrics(metrics: JSONObject) {
        listOf(
            "decision_event_count",
            "selected_context_count_total",
            "reused_context_count_total",
            "required_confirmation_count_total",
            "last_selected_context_count",
            "last_reused_context_count",
            "last_required_confirmation_count",
        ).forEach { field ->
            if (!metrics.has(field)) metrics.put(field, 0)
        }
        if (!metrics.has("last_decision_event_id")) metrics.put("last_decision_event_id", "")
    }

    private fun latestEnvelopeId(state: JSONObject): String? {
        val order = state.getJSONArray("share_order")
        return if (order.length() == 0) null else order.getString(order.length() - 1)
    }

    private fun recordDecisionTrace(
        state: JSONObject,
        step: ProbeInputStep,
        decision: String,
        selectedIds: Set<String>,
        causeOutcomeId: String?,
        causeEvidenceIds: List<String>,
        actionRecordEventId: String?,
    ) {
        val facts = state.getJSONObject("facts")
        val reusedContextCount = selectedIds.count { selectedId ->
            val fact = facts.optJSONObject(selectedId) ?: return@count false
            fact.optString("session_id", "").isNotEmpty() &&
                fact.optString("session_id") != step.sessionId
        }
        val requiredConfirmationCount = if (decision == "ASK") 1 else 0
        val currentEvidenceId = "evidence-${safeId(step.eventId)}"
        val trace = JSONObject()
            .put("event_id", step.eventId)
            .put("session_id", step.sessionId)
            .put("decision_state", decision)
            .put("decision_goal", state.optString("last_decision_goal", ""))
            .put("decision_target", state.optString("last_decision_target", ""))
            .put("selected_context_ids", JSONArray(selectedIds.toList()))
            .put("selected_context_count", selectedIds.size)
            .put("reused_context_count", reusedContextCount)
            .put("required_confirmation_count", requiredConfirmationCount)
            .put(
                "cause_outcome_ids",
                JSONArray(causeOutcomeId?.let(::listOf) ?: emptyList<String>()),
            )
            .put("cause_evidence_ids", JSONArray(causeEvidenceIds.distinct()))
            .put("result_evidence_id", currentEvidenceId)
            .put("process_epoch", state.getInt("process_epoch"))
            .put("reset_generation", state.getInt("probe_reset_generation"))
            .put("action_record_event_id", actionRecordEventId ?: "")
        state.getJSONObject("decision_traces").put(step.eventId, trace)

        val metrics = state.getJSONObject("probe_interaction_metrics")
        metrics.put("decision_event_count", metrics.getInt("decision_event_count") + 1)
            .put(
                "selected_context_count_total",
                metrics.getInt("selected_context_count_total") + selectedIds.size,
            )
            .put(
                "reused_context_count_total",
                metrics.getInt("reused_context_count_total") + reusedContextCount,
            )
            .put(
                "required_confirmation_count_total",
                metrics.getInt("required_confirmation_count_total") + requiredConfirmationCount,
            )
            .put("last_decision_event_id", step.eventId)
            .put("last_selected_context_count", selectedIds.size)
            .put("last_reused_context_count", reusedContextCount)
            .put("last_required_confirmation_count", requiredConfirmationCount)
    }

    private data class PendingOutcomeMatch(
        val id: String?,
        val descriptor: JSONObject?,
        val ambiguous: Boolean,
    )

    /** Resolve goal/entity as one correlation key; never splice two unrelated contexts. */
    private fun delayedOutcomeCorrelation(
        state: JSONObject,
        facts: JSONObject,
        explicitGoal: String?,
        explicitTarget: String?,
    ): Pair<String, String> {
        if (explicitGoal != null && explicitTarget != null) {
            return explicitGoal to explicitTarget
        }

        val pending = state.getJSONObject("pending_delayed_outcomes")
        val pendingDescriptors = pending.keys().asSequence()
            .mapNotNull(pending::optJSONObject)
            .toList()
        val lastGoal = state.optString("last_decision_goal", "")
        val lastTarget = state.optString("last_decision_target", "")
            .takeIf { it != "default" }
            .orEmpty()

        if (explicitGoal != null) {
            val matchingTargets = pendingDescriptors.asSequence()
                .filter { it.optString("goal", "") == explicitGoal }
                .map { it.optString("entity", "") }
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val target = when {
                matchingTargets.size == 1 -> matchingTargets.single()
                matchingTargets.size > 1 -> ""
                state.getJSONObject("route_rules").optString(explicitGoal, "").isNotEmpty() ->
                    state.getJSONObject("route_rules").getString(explicitGoal)
                lastGoal == explicitGoal -> lastTarget
                else -> ""
            }
            return explicitGoal to target
        }

        if (explicitTarget != null) {
            val matchingGoals = pendingDescriptors.asSequence()
                .filter { it.optString("entity", "") == explicitTarget }
                .map { it.optString("goal", "") }
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val activeFactGoals = facts.keys().asSequence()
                .mapNotNull(facts::optJSONObject)
                .filter {
                    it.optString("status", "ACTIVE") == "ACTIVE" &&
                        it.optString("entity", "") == explicitTarget
                }
                .map { it.optString("goal", "") }
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            val goal = when {
                matchingGoals.size == 1 -> matchingGoals.single()
                matchingGoals.size > 1 -> ""
                activeFactGoals.size == 1 -> activeFactGoals.single()
                lastTarget == explicitTarget -> lastGoal
                else -> ""
            }
            return goal to explicitTarget
        }

        return if (lastGoal.isNotEmpty() && lastTarget.isNotEmpty()) {
            lastGoal to lastTarget
        } else {
            "" to ""
        }
    }

    private fun selectPendingDelayedOutcome(
        state: JSONObject,
        requestedGoal: String,
        requestedTarget: String,
    ): PendingOutcomeMatch {
        val pending = state.getJSONObject("pending_delayed_outcomes")
        val orderedIds = (
            strings(state.getJSONArray("pending_delayed_outcome_order")) +
                pending.keys().asSequence().filterNot {
                    strings(state.getJSONArray("pending_delayed_outcome_order")).contains(it)
                }.sorted()
            ).distinct()
        val candidates = orderedIds.filter { pendingId ->
            val descriptor = pending.optJSONObject(pendingId) ?: return@filter false
            (requestedGoal.isEmpty() || descriptor.optString("goal") == requestedGoal) &&
                (requestedTarget.isEmpty() || descriptor.optString("entity") == requestedTarget)
        }
        if (candidates.size != 1) {
            return PendingOutcomeMatch(
                id = null,
                descriptor = null,
                ambiguous = candidates.size > 1,
            )
        }
        val selectedId = candidates.single()
        return PendingOutcomeMatch(
            id = selectedId,
            descriptor = pending.getJSONObject(selectedId),
            ambiguous = false,
        )
    }

    private fun invalidatePendingOutcomesDependingOn(
        state: JSONObject,
        invalidatedFactIds: Set<String>,
    ): Set<String> {
        if (invalidatedFactIds.isEmpty()) return emptySet()
        val pending = state.getJSONObject("pending_delayed_outcomes")
        val invalidatedPendingIds = linkedSetOf<String>()
        pending.keys().asSequence().toList().forEach { pendingId ->
            val descriptor = pending.getJSONObject(pendingId)
            val dependencies = descriptor.optJSONArray("dependency_ids")
                ?.let(::strings)
                .orEmpty()
            if (dependencies.any(invalidatedFactIds::contains)) {
                val value = descriptor.optString("value", "")
                recordInvalidatedPendingOutcomeScope(
                    state = state,
                    goal = descriptor.optString("goal", ""),
                    target = descriptor.optString("entity", ""),
                )
                appendUnique(
                    state.getJSONArray("invalidated_pending_outcome_fingerprints"),
                    pendingOutcomeFingerprint(
                        descriptor.optString("goal", ""),
                        descriptor.optString("entity", ""),
                        value,
                    ),
                )
                appendUnique(
                    state.getJSONArray("invalidated_pending_outcome_value_fingerprints"),
                    Sha256.utf8(value),
                )
                pending.remove(pendingId)
                removeString(state.getJSONArray("pending_delayed_outcome_order"), pendingId)
                appendUnique(state.getJSONArray("tombstones"), tombstoneId(pendingId))
                invalidatedPendingIds += pendingId
            }
        }
        return invalidatedPendingIds
    }

    private fun invalidateDeliveredOutcomesDependingOn(
        state: JSONObject,
        invalidatedFactIds: Set<String>,
    ): Set<String> {
        if (invalidatedFactIds.isEmpty()) return emptySet()
        val outcomes = state.getJSONObject("delayed_outcomes")
        val invalidatedOutcomeIds = linkedSetOf<String>()
        outcomes.keys().asSequence().toList().forEach { outcomeId ->
            val outcome = outcomes.getJSONObject(outcomeId)
            val dependencies = outcome.optJSONArray("dependency_ids")
                ?.let(::strings)
                .orEmpty()
            if (
                outcome.optString("status", "ACTIVE") != "INVALIDATED" &&
                dependencies.any(invalidatedFactIds::contains)
            ) {
                val value = outcome.optString("value", "")
                recordInvalidatedPendingOutcomeScope(
                    state = state,
                    goal = outcome.optString("goal", ""),
                    target = outcome.optString("entity", ""),
                )
                appendUnique(
                    state.getJSONArray("invalidated_pending_outcome_fingerprints"),
                    pendingOutcomeFingerprint(
                        outcome.optString("goal", ""),
                        outcome.optString("entity", ""),
                        value,
                    ),
                )
                appendUnique(
                    state.getJSONArray("invalidated_pending_outcome_value_fingerprints"),
                    Sha256.utf8(value),
                )
                outcome.remove("value")
                outcome.put("status", "INVALIDATED")
                    .put("review_state", "INVALIDATED")
                    .put("invalidated_reason", "source-context-invalidated")
                invalidatedOutcomeIds += outcomeId
                appendUnique(state.getJSONArray("tombstones"), tombstoneId(outcomeId))
            }
        }
        return invalidatedOutcomeIds
    }

    private fun pendingOutcomeFingerprint(goal: String, target: String, value: String): String =
        Sha256.utf8(CanonicalJson.encode(JSONArray().put(goal).put(target).put(value)))

    private fun pendingOutcomeScopeFingerprint(goal: String, target: String): String =
        Sha256.utf8(CanonicalJson.encode(JSONArray().put(goal).put(target)))

    private fun recordInvalidatedPendingOutcomeScope(
        state: JSONObject,
        goal: String,
        target: String,
    ) {
        if (goal.isEmpty() || target.isEmpty()) return
        appendUnique(
            state.getJSONArray("invalidated_pending_outcome_scope_fingerprints"),
            pendingOutcomeScopeFingerprint(goal, target),
        )
    }

    private fun isPendingOutcomeScopeInvalidated(
        state: JSONObject,
        goal: String,
        target: String,
    ): Boolean = strings(
        state.getJSONArray("invalidated_pending_outcome_scope_fingerprints"),
    ).contains(pendingOutcomeScopeFingerprint(goal, target))

    private fun latestOutcomeId(state: JSONObject, goal: String, target: String): String? {
        val outcomes = state.getJSONObject("delayed_outcomes")
        val order = (
            strings(state.getJSONArray("delayed_outcome_order")) +
                outcomes.keys().asSequence().filterNot {
                    strings(state.getJSONArray("delayed_outcome_order")).contains(it)
                }.sorted()
            ).distinct()
        return order.asReversed().firstOrNull { outcomeId ->
            val outcome = outcomes.optJSONObject(outcomeId) ?: return@firstOrNull false
            val outcomeGoal = outcome.optString("goal")
            val outcomeTarget = outcome.optString("entity")
            outcome.optString("status", "ACTIVE") != "INVALIDATED" &&
                (goal.isEmpty() || outcomeGoal.isEmpty() || outcomeGoal == goal) &&
                (target.isEmpty() || outcomeTarget.isEmpty() || outcomeTarget == target)
        }
    }

    private fun isRevoked(state: JSONObject, target: String): Boolean =
        strings(state.getJSONArray("revoked_targets")).contains(target)

    private fun isScopeRevoked(
        state: JSONObject,
        goal: String,
        target: String,
        scope: String,
    ): Boolean {
        if (scope.isEmpty()) return false
        val rules = state.getJSONObject("revoked_scope_rules")
        return rules.keys().asSequence().any { ruleId ->
            val rule = rules.getJSONObject(ruleId)
            rule.optString("goal") == goal &&
                rule.optString("entity") == target &&
                rule.optString("scope") == scope
        }
    }

    private fun recordScopeRevocationRule(
        state: JSONObject,
        goal: String,
        target: String,
        scope: String,
    ): String {
        val descriptor = JSONObject()
            .put("goal", goal)
            .put("entity", target)
            .put("scope", scope)
        val ruleId = scopeRevocationId(goal, target, scope)
        state.getJSONObject("revoked_scope_rules").put(ruleId, descriptor)
        appendUnique(state.getJSONArray("tombstones"), tombstoneId(ruleId))
        return ruleId
    }

    private fun scopeRevocationId(goal: String, target: String, scope: String): String =
        "revoked-scope-${Sha256.canonicalJson(JSONArray().put(goal).put(target).put(scope)).take(24)}"

    private fun changedContextFields(
        oldTarget: String,
        oldContext: JSONObject,
        newTarget: String,
        newContext: JSONObject,
    ): LinkedHashSet<String> = linkedSetOf<String>().apply {
        if (oldTarget != newTarget) add("TARGET_ROOM")
        if (oldContext.optString("room_display") != newContext.optString("room_display")) {
            add("ROOM_PROFILE")
        }
        if (oldContext.optString("current_role") != newContext.optString("current_role")) {
            add("CURRENT_ROLE")
        }
        if (oldContext.optString("allowed_scope") != newContext.optString("allowed_scope")) {
            add("ALLOWED_SCOPE")
        }
        if (
            oldContext.optString("link_access").uppercase() !=
            newContext.optString("link_access").uppercase()
        ) {
            add("LINK_ACCESS")
        }
    }

    private fun ensureProcessEpoch(state: JSONObject) {
        val previous = state.optString("process_marker", "")
        if (previous != processMarker) {
            if (previous.isNotEmpty()) {
                state.put("process_epoch", state.getInt("process_epoch") + 1)
                state.getJSONObject("safe_share_context")
                    .put("process_epoch", state.getInt("process_epoch"))
                if (
                    state.optJSONObject("pending_route_change") != null ||
                    state.optJSONObject("pending_context_change") != null
                ) {
                    beginProductSession(state)
                    recordProductEpisode(state, "E4_RELAUNCH_PENDING")
                }
            }
            state.put("process_marker", processMarker)
        }
    }

    private fun normalized(source: JSONObject): JSONObject = source.apply {
        if (!has("facts")) put("facts", JSONObject())
        if (!has("actions")) put("actions", JSONObject())
        if (!has("drafts")) put("drafts", JSONObject())
        if (!has("draft_parts")) put("draft_parts", JSONObject())
        if (!has("route_rules")) put("route_rules", JSONObject())
        if (!has("share_envelopes")) put("share_envelopes", JSONObject())
        if (!has("processed_share_events")) put("processed_share_events", JSONObject())
        if (!has("delayed_outcomes")) put("delayed_outcomes", JSONObject())
        if (!has("pending_delayed_outcomes")) put("pending_delayed_outcomes", JSONObject())
        if (!has("fact_deletion_rules")) put("fact_deletion_rules", JSONObject())
        if (!has("revoked_scope_rules")) put("revoked_scope_rules", JSONObject())
        if (!has("decision_traces")) put("decision_traces", JSONObject())
        if (!has("active_probe_run_id")) put("active_probe_run_id", "")
        if (!has("action_dependencies")) put("action_dependencies", JSONObject())
        if (!has("decision_action_bindings")) put("decision_action_bindings", JSONObject())
        if (!has("share_order")) put("share_order", JSONArray())
        if (!has("action_order")) put("action_order", JSONArray())
        if (!has("draft_order")) put("draft_order", JSONArray())
        if (!has("delayed_outcome_order")) put("delayed_outcome_order", JSONArray())
        if (!has("pending_delayed_outcome_order")) {
            put("pending_delayed_outcome_order", JSONArray())
        }
        if (!has("invalidated_pending_outcome_fingerprints")) {
            put("invalidated_pending_outcome_fingerprints", JSONArray())
        }
        if (!has("invalidated_pending_outcome_value_fingerprints")) {
            put("invalidated_pending_outcome_value_fingerprints", JSONArray())
        }
        if (!has("invalidated_pending_outcome_scope_fingerprints")) {
            put("invalidated_pending_outcome_scope_fingerprints", JSONArray())
        }
        if (!has("tombstones")) put("tombstones", JSONArray())
        if (!has("tombstoned_fact_ids")) put("tombstoned_fact_ids", JSONArray())
        if (!has("tombstoned_fact_fingerprints")) {
            put("tombstoned_fact_fingerprints", JSONArray())
        }
        if (!has("tombstoned_share_event_ids")) {
            put("tombstoned_share_event_ids", JSONArray())
        }
        if (!has("revoked_scopes")) put("revoked_scopes", JSONArray())
        if (!has("revoked_targets")) put("revoked_targets", JSONArray())
        if (!has("revoked_scope_labels")) put("revoked_scope_labels", JSONArray())
        if (!has("receipts")) put("receipts", JSONArray())
        if (!has("evidence")) put("evidence", JSONArray())
        if (!has("product_episode_history")) put("product_episode_history", JSONArray())
        if (!has("network")) put("network", "ONLINE")
        if (!has("process_epoch")) put("process_epoch", 0)
        if (!has("process_marker")) put("process_marker", "")
        if (!has("decision_request_count")) put("decision_request_count", 0)
        if (!has("probe_reset_generation")) put("probe_reset_generation", 0)
        if (!has("active_purpose")) put("active_purpose", "")
        if (!has("episode")) put("episode", "NOT_STARTED")
        if (!has("latest_outcome_id")) put("latest_outcome_id", "")
        if (!has("external_dispatch_count")) put("external_dispatch_count", 0)
        if (!has("product_session_counter")) put("product_session_counter", 0)
        if (!has("product_session_id")) put("product_session_id", "")
        if (!has("last_decision_goal")) put("last_decision_goal", "")
        if (!has("last_decision_target")) put("last_decision_target", "")
        if (!has("product_interaction_metrics")) {
            put("product_interaction_metrics", newProductInteractionMetrics())
        } else {
            normalizeProductInteractionMetrics(getJSONObject("product_interaction_metrics"))
        }
        if (!has("probe_interaction_metrics")) {
            put("probe_interaction_metrics", newProbeInteractionMetrics())
        } else {
            normalizeProbeInteractionMetrics(getJSONObject("probe_interaction_metrics"))
        }
        if (!has("safe_share_context")) put("safe_share_context", JSONObject())
    }

    private fun factId(target: String, kind: String, eventId: String): String =
        "fact-${safeId(target)}-${safeId(kind.lowercase())}-${safeId(eventId)}"

    private fun productRawFactId(eventId: String): String =
        "fact-product-share-${safeId(eventId)}-ephemeral"

    private fun productRouteFactId(purpose: String, target: String): String =
        "fact-product-route-${safeId(purpose)}-${safeId(target)}"

    private fun productActionEventId(eventId: String, target: String): String =
        "product-action-${safeId(eventId)}-${safeId(target)}"

    private fun shareEventTombstoneId(eventId: String): String =
        "tombstone-share-${safeId(eventId)}"

    private fun role(roles: JSONObject, key: String): String? = when (val value = roles.opt(key)) {
        null, JSONObject.NULL -> null
        is String -> value.takeUnless(String::isBlank)?.let { text ->
            if (text.startsWith(SEMANTIC_SCALAR_PREFIX)) {
                "${SEMANTIC_SCALAR_PREFIX}string:$text"
            } else {
                text
            }
        }
        is Number -> "${SEMANTIC_SCALAR_PREFIX}number:${CanonicalJson.encode(JSONArray().put(value))}"
        is Boolean -> "${SEMANTIC_SCALAR_PREFIX}boolean:$value"
        else -> null
    }

    private fun protocolRole(roles: JSONObject, key: String): String? =
        (roles.opt(key) as? String)?.takeUnless(String::isBlank)

    private fun safeId(value: String): String = SafeShareId.component(value)

    private fun appendUnique(array: JSONArray, value: String) {
        if (strings(array).none { it == value }) array.put(value)
    }

    private fun removeString(array: JSONArray, value: String) {
        for (index in array.length() - 1 downTo 0) {
            if (array.optString(index) == value) array.remove(index)
        }
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun copyArray(array: JSONArray): JSONArray = JSONArray(CanonicalJson.encode(array))

    private fun clearObject(value: JSONObject) {
        value.keys().asSequence().toList().forEach(value::remove)
    }

    private fun clearArray(value: JSONArray) {
        while (value.length() > 0) value.remove(value.length() - 1)
    }

    private companion object {
        const val FACT_CANDIDATE_PREFIX = "fact:"
        const val PART_CANDIDATE_PREFIX = "part:"
        const val SEMANTIC_SCALAR_PREFIX = "\u001eSCPC_SCALAR:"
    }
}
