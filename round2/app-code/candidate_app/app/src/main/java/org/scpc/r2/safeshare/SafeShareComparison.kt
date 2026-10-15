package org.scpc.r2.safeshare

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.Sha256

/**
 * Local paired observation for the Mission's signature mechanism.
 *
 * Every pair starts from byte-identical synthetic state in isolated stores.
 * The full arm enables confirmed-route reuse and dependency-scoped recovery;
 * claim-off disables exactly those two parts of the declared mechanism.  The
 * output contains raw observations and deltas only, never a score or verdict.
 */
class SafeShareComparison(
    private val storeForNamespace: (String) -> RelayStateStore,
    private val runId: String,
    private val virtualClockMillis: Long,
) {
    fun run(): JSONObject {
        val fixtures = fixtures(virtualClockMillis)
        val fixtureArray = JSONArray().apply {
            fixtures.forEach { put(JSONObject(CanonicalJson.encode(it))) }
        }
        val fixtureSetHash = Sha256.canonicalJson(
            JSONObject().put("fixtures", fixtureArray),
        )
        val pairs = JSONArray()

        fixtures.forEach { fixture ->
            val pairId = fixture.getString("pair_id")
            val pairPrefix = "cmp-${safeNamespace(runId)}-${safeNamespace(pairId)}"
            val fullNamespace = "$pairPrefix-full"
            val claimOffNamespace = "$pairPrefix-claim-off"
            val fullStore = storeForNamespace(fullNamespace)
            val claimOffStore = storeForNamespace(claimOffNamespace)
            require(fullStore !== claimOffStore) {
                "comparison namespaces must resolve to distinct stores"
            }
            require(fullStore.load().length() == 0 && claimOffStore.load().length() == 0) {
                "comparison stores must start empty"
            }

            val processMarker0 = "$pairPrefix-process-0"
            val initialSnapshot = prepareInitialSnapshot(fixture, processMarker0)
            val initialSnapshotBytes = CanonicalJson.encode(initialSnapshot)
            val initialSnapshotHash = Sha256.utf8(initialSnapshotBytes)
            fullStore.save(JSONObject(initialSnapshotBytes))
            claimOffStore.save(JSONObject(initialSnapshotBytes))

            val full = runArm(
                fixture = fixture,
                fixtureSetHash = fixtureSetHash,
                namespace = fullNamespace,
                store = fullStore,
                processMarker0 = processMarker0,
                processMarker1 = "$pairPrefix-process-1",
                confirmedRouteReuseEnabled = true,
                selectiveImpactRecoveryEnabled = true,
            )
            val fullHashBeforeClaimOff = Sha256.canonicalJson(fullStore.load())
            val claimOffInitialStateHash = Sha256.canonicalJson(claimOffStore.load())
            val claimOffInitialStateKeyCount = claimOffStore.load().length()
            val claimOff = runArm(
                fixture = fixture,
                fixtureSetHash = fixtureSetHash,
                namespace = claimOffNamespace,
                store = claimOffStore,
                processMarker0 = processMarker0,
                processMarker1 = "$pairPrefix-process-1",
                confirmedRouteReuseEnabled = false,
                selectiveImpactRecoveryEnabled = false,
            )
            val fullHashAfterClaimOff = Sha256.canonicalJson(fullStore.load())

            val fullMetrics = full.getJSONObject("metrics")
            val claimOffMetrics = claimOff.getJSONObject("metrics")
            pairs.put(
                JSONObject()
                    .put("pair_id", pairId)
                    .put("surface", fixture.getString("surface"))
                    .put("fixture_sha256", Sha256.canonicalJson(fixture))
                    .put("initial_snapshot_sha256", initialSnapshotHash)
                    .put("full_initial_snapshot_sha256", full.getString("initial_snapshot_sha256"))
                    .put("claim_off_initial_snapshot_sha256", claimOffInitialStateHash)
                    .put("claim_off_initial_state_key_count", claimOffInitialStateKeyCount)
                    .put("full", full)
                    .put("claim_off", claimOff)
                    .put(
                        "observed_delta",
                        JSONObject()
                            .put(
                                "e3_affected_draft_count",
                                claimOffMetrics.getInt("e3_affected_draft_count") -
                                    fullMetrics.getInt("e3_affected_draft_count"),
                            )
                            .put(
                                "e4_invalidated_draft_count",
                                claimOffMetrics.getInt("e4_invalidated_draft_count") -
                                    fullMetrics.getInt("e4_invalidated_draft_count"),
                            )
                            .put(
                                "e4_replacement_draft_count",
                                claimOffMetrics.getInt("e4_replacement_draft_count") -
                                    fullMetrics.getInt("e4_replacement_draft_count"),
                            )
                            .put(
                                "late_route_confirmation_required_count",
                                claimOffMetrics.getInt("late_route_confirmation_required_count") -
                                    fullMetrics.getInt("late_route_confirmation_required_count"),
                            )
                            .put(
                                "persisted_state_utf8_bytes",
                                claimOffMetrics.getInt("persisted_state_utf8_bytes") -
                                    fullMetrics.getInt("persisted_state_utf8_bytes"),
                            )
                            .put(
                                "active_ephemeral_value_count",
                                claimOffMetrics.getInt("active_ephemeral_value_count") -
                                    fullMetrics.getInt("active_ephemeral_value_count"),
                            )
                            .put(
                                "tombstone_count",
                                claimOffMetrics.getInt("tombstone_count") -
                                    fullMetrics.getInt("tombstone_count"),
                            ),
                    )
                    .put("full_state_sha256_before_claim_off", fullHashBeforeClaimOff)
                    .put("full_state_sha256_after_claim_off", fullHashAfterClaimOff),
            )
        }

        return JSONObject()
            .put("schema_version", "safeshare-comparison-v3")
            .put("comparison_status", "LOCAL_PAIRED_PRODUCTION_CORE_OBSERVATION")
            .put("run_id", runId)
            .put("virtual_clock_millis", virtualClockMillis)
            .put("fixture_set_sha256", fixtureSetHash)
            .put(
                "shared_conditions",
                JSONObject()
                    .put("input_fixture_set_sha256", fixtureSetHash)
                    .put("virtual_clock_millis", virtualClockMillis)
                    .put("network", "LOCAL_ONLY")
                    .put("model_backend", "NOT_USED")
                    .put("model_invocation_count", 0),
            )
            .put(
                "mechanism_switch",
                JSONObject()
                    .put(
                        "full",
                        JSONObject()
                            .put("confirmed_route_reuse_enabled", true)
                            .put("selective_impact_recovery_enabled", true),
                    )
                    .put(
                        "claim_off",
                        JSONObject()
                            .put("confirmed_route_reuse_enabled", false)
                            .put("selective_impact_recovery_enabled", false),
                    ),
            )
            .put("pairs", pairs)
            .put("aggregate", aggregate(pairs))
    }

    private fun prepareInitialSnapshot(
        fixture: JSONObject,
        processMarker: String,
    ): JSONObject {
        val store = InMemoryRelayStateStore()
        val core = SafeShareCore(store, processMarker)
        val purpose = fixture.getString("purpose")
        val target = fixture.getString("target")

        val e1 = payload(
            fixture.getString("e1_event_id"),
            fixture.getString("e1_kind"),
            fixture.getString("e1_content"),
        )
        rememberAndBind(core, fixture, e1, "INITIAL_REVIEW")
        core.receiveSharedText(e1.eventId, e1.review.safePreview)
        core.proposeRoute(purpose, target)
        core.confirmPendingRoute()

        listOf("e2_a", "e2_b").forEach { prefix ->
            val next = payload(
                fixture.getString("${prefix}_event_id"),
                "LINK",
                fixture.getString("${prefix}_content"),
            )
            rememberAndBind(core, fixture, next, "DRAFT_REVIEWED")
            core.receiveSharedText(next.eventId, next.review.safePreview)
        }
        return store.load()
    }

    private fun runArm(
        fixture: JSONObject,
        fixtureSetHash: String,
        namespace: String,
        store: RelayStateStore,
        processMarker0: String,
        processMarker1: String,
        confirmedRouteReuseEnabled: Boolean,
        selectiveImpactRecoveryEnabled: Boolean,
    ): JSONObject {
        val initialSnapshotHash = Sha256.canonicalJson(store.load())
        val initialCore = SafeShareCore(
            store = store,
            processMarker = processMarker0,
            confirmedRouteReuseEnabled = confirmedRouteReuseEnabled,
            selectiveImpactRecoveryEnabled = selectiveImpactRecoveryEnabled,
        )
        val initialProduct = initialCore.productSnapshot()
        val e3 = initialCore.beginContextChange(
            purpose = fixture.getString("purpose"),
            targetRoomId = fixture.getString("target"),
            roomDisplay = fixture.getString("room_display"),
            currentRole = fixture.getString("current_role"),
            allowedScope = fixture.getString("allowed_scope"),
            linkAccess = "UNKNOWN",
        )
        val pending = e3.getJSONObject("pending_context_change")

        val relaunchedCore = SafeShareCore(
            store = store,
            processMarker = processMarker1,
            confirmedRouteReuseEnabled = confirmedRouteReuseEnabled,
            selectiveImpactRecoveryEnabled = selectiveImpactRecoveryEnabled,
        )
        relaunchedCore.revisePendingContextChange(
            targetRoomId = fixture.getString("target"),
            roomDisplay = fixture.getString("room_display"),
            currentRole = fixture.getString("current_role"),
            allowedScope = fixture.getString("allowed_scope"),
            linkAccess = "CONFIRMED",
        )
        val e4 = relaunchedCore.confirmPendingContextChange()
        val recovery = e4.getJSONObject("last_recovery")

        val latePayload = payload(
            fixture.getString("late_event_id"),
            fixture.getString("late_kind"),
            fixture.getString("late_content"),
        )
        rememberAndBind(relaunchedCore, fixture, latePayload, "LATE_HORIZON_REVIEW")
        val late = relaunchedCore.receiveSharedText(
            latePayload.eventId,
            latePayload.review.safePreview,
        )
        val beforeDuplicate = relaunchedCore.productSnapshot()
        val duplicate = relaunchedCore.receiveSharedText(
            latePayload.eventId,
            latePayload.review.safePreview,
        )
        val afterDuplicate = relaunchedCore.productSnapshot()
        val finalState = store.load()
        val burden = stateBurdenMetrics(finalState)
        val duplicateCommitCount = (
            afterDuplicate.getInt("active_draft_count") -
                beforeDuplicate.getInt("active_draft_count")
            ).coerceAtLeast(0)

        return JSONObject()
            .put("namespace", namespace)
            .put("fixture_set_sha256", fixtureSetHash)
            .put("initial_snapshot_sha256", initialSnapshotHash)
            .put("confirmed_route_reuse_enabled", confirmedRouteReuseEnabled)
            .put("selective_impact_recovery_enabled", selectiveImpactRecoveryEnabled)
            .put("process_markers", JSONArray(listOf(processMarker0, processMarker1)))
            .put(
                "metrics",
                JSONObject()
                    .put("initial_active_draft_count", initialProduct.getInt("active_draft_count"))
                    .put("e3_affected_draft_count", pending.getInt("affected_draft_count"))
                    .put("e3_impact_selection_mode", pending.getString("impact_selection_mode"))
                    .put(
                        "e4_invalidated_draft_count",
                        recovery.getInt("invalidated_draft_count"),
                    )
                    .put(
                        "e4_replacement_draft_count",
                        recovery.getInt("replacement_draft_count"),
                    )
                    .put("late_status", late.getString("status"))
                    .put("late_route_reused", late.getBoolean("route_reused"))
                    .put(
                        "late_route_confirmation_required_count",
                        late.getInt("route_confirmation_required_count"),
                    )
                    .put("duplicate_event_count", if (duplicate.getBoolean("duplicate")) 1 else 0)
                    .put("duplicate_commit_count", duplicateCommitCount)
                    .put("process_epoch", afterDuplicate.getInt("process_epoch"))
                    .put("active_draft_count", afterDuplicate.getInt("active_draft_count"))
                    .put("held_draft_count", afterDuplicate.getInt("held_draft_count"))
                    .put("cancelled_draft_count", afterDuplicate.getInt("cancelled_draft_count"))
                    .put("external_dispatch_count", afterDuplicate.getInt("external_dispatch_count"))
                    .put(
                        "persisted_state_utf8_bytes",
                        burden.getInt("persisted_state_utf8_bytes"),
                    )
                    .put(
                        "active_ephemeral_value_count",
                        burden.getInt("active_ephemeral_value_count"),
                    )
                    .put("share_envelope_count", burden.getInt("share_envelope_count"))
                    .put("tombstone_count", burden.getInt("tombstone_count")),
            )
            .put("state_sha256", Sha256.canonicalJson(finalState))
    }

    /** Count-only privacy/resource observations; no retained value is emitted. */
    private fun stateBurdenMetrics(state: JSONObject): JSONObject {
        val facts = state.optJSONObject("facts") ?: JSONObject()
        val activeEphemeralValues = facts.keys().asSequence().count { factId ->
            val fact = facts.optJSONObject(factId) ?: return@count false
            fact.optString("status", "ACTIVE") == "ACTIVE" &&
                fact.optString("kind", "") == "EPHEMERAL" &&
                fact.optString("value", "").isNotEmpty()
        }
        return JSONObject()
            .put(
                "persisted_state_utf8_bytes",
                CanonicalJson.encode(state).toByteArray(Charsets.UTF_8).size,
            )
            .put("active_ephemeral_value_count", activeEphemeralValues)
            .put("share_envelope_count", state.optJSONObject("share_envelopes")?.length() ?: 0)
            .put("tombstone_count", state.optJSONArray("tombstones")?.length() ?: 0)
    }

    private fun rememberAndBind(
        core: SafeShareCore,
        fixture: JSONObject,
        payload: SafeSharePayload,
        reviewState: String,
    ) {
        core.rememberReviewedPayload(payload)
        core.updateSafetyContext(
            purpose = fixture.getString("purpose"),
            roomId = fixture.getString("target"),
            roomDisplay = fixture.getString("room_display"),
            currentRole = fixture.getString("current_role"),
            allowedScope = fixture.getString("allowed_scope"),
            linkAccess = if (payload.review.kind == ShareKind.LINK) {
                "PROJECT_ONLY"
            } else {
                "NOT_APPLICABLE"
            },
            contentKind = payload.review.kind.name,
            reviewState = reviewState,
        )
    }

    private fun payload(eventId: String, kind: String, content: String): SafeSharePayload {
        val shareKind = ShareKind.valueOf(kind)
        val review = if (shareKind == ShareKind.LINK) {
            SafeShareLinkReview.review(
                raw = content,
                host = runCatching { URI(content).host }.getOrNull(),
            )
        } else {
            SafeSharePolicy.review(shareKind, content)
        }
        return SafeSharePayload(eventId, review, privatePdfCopied = shareKind == ShareKind.PDF)
    }

    private fun aggregate(pairs: JSONArray): JSONObject {
        var fullAffected = 0
        var claimOffAffected = 0
        var fullReplacement = 0
        var claimOffReplacement = 0
        var fullConfirmations = 0
        var claimOffConfirmations = 0
        var fullDispatches = 0
        var claimOffDispatches = 0
        var fullStateBytes = 0
        var claimOffStateBytes = 0
        var fullEphemeralValues = 0
        var claimOffEphemeralValues = 0
        var fullTombstones = 0
        var claimOffTombstones = 0
        for (index in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(index)
            val full = pair.getJSONObject("full").getJSONObject("metrics")
            val claimOff = pair.getJSONObject("claim_off").getJSONObject("metrics")
            fullAffected += full.getInt("e3_affected_draft_count")
            claimOffAffected += claimOff.getInt("e3_affected_draft_count")
            fullReplacement += full.getInt("e4_replacement_draft_count")
            claimOffReplacement += claimOff.getInt("e4_replacement_draft_count")
            fullConfirmations += full.getInt("late_route_confirmation_required_count")
            claimOffConfirmations += claimOff.getInt("late_route_confirmation_required_count")
            fullDispatches += full.getInt("external_dispatch_count")
            claimOffDispatches += claimOff.getInt("external_dispatch_count")
            fullStateBytes += full.getInt("persisted_state_utf8_bytes")
            claimOffStateBytes += claimOff.getInt("persisted_state_utf8_bytes")
            fullEphemeralValues += full.getInt("active_ephemeral_value_count")
            claimOffEphemeralValues += claimOff.getInt("active_ephemeral_value_count")
            fullTombstones += full.getInt("tombstone_count")
            claimOffTombstones += claimOff.getInt("tombstone_count")
        }
        return JSONObject()
            .put("pair_count", pairs.length())
            .put("full_e3_affected_draft_count", fullAffected)
            .put("claim_off_e3_affected_draft_count", claimOffAffected)
            .put("full_e4_replacement_draft_count", fullReplacement)
            .put("claim_off_e4_replacement_draft_count", claimOffReplacement)
            .put("full_late_route_confirmation_required_count", fullConfirmations)
            .put("claim_off_late_route_confirmation_required_count", claimOffConfirmations)
            .put("full_external_dispatch_count", fullDispatches)
            .put("claim_off_external_dispatch_count", claimOffDispatches)
            .put("full_persisted_state_utf8_bytes", fullStateBytes)
            .put("claim_off_persisted_state_utf8_bytes", claimOffStateBytes)
            .put("full_active_ephemeral_value_count", fullEphemeralValues)
            .put("claim_off_active_ephemeral_value_count", claimOffEphemeralValues)
            .put("full_tombstone_count", fullTombstones)
            .put("claim_off_tombstone_count", claimOffTombstones)
    }

    private fun fixtures(clockMillis: Long): List<JSONObject> = listOf(
        fixture(
            pairId = "CMP-TEXT-LINK-01",
            surface = "TEXT_TO_LINK_LATE_HORIZON",
            clockMillis = clockMillis,
            purpose = "alpha-expense-draft",
            target = "room-alpha-7f3a",
            e1Kind = "TEXT",
            e1Content = "합성 정산 메모 api_key=SCPC_SYNTHETIC_ONLY_COMPARISON",
            lateKind = "TEXT",
            lateContent = "합성 재실행 후 정산 메모",
        ),
        fixture(
            pairId = "CMP-PDF-LINK-02",
            surface = "PDF_TO_LINK_LATE_HORIZON",
            clockMillis = clockMillis,
            purpose = "delta-review-draft",
            target = "room-delta-91bd",
            e1Kind = "PDF",
            e1Content = "PDF: synthetic-delta-review-v1.pdf (local fixture)",
            lateKind = "PDF",
            lateContent = "PDF: synthetic-delta-review-v2.pdf (local fixture)",
        ),
        fixture(
            pairId = "CMP-LINK-LINK-03",
            surface = "LINK_TO_LINK_LATE_HORIZON",
            clockMillis = clockMillis,
            purpose = "omega-release-note",
            target = "room-omega-c42e",
            e1Kind = "LINK",
            e1Content = "https://docs.example.invalid/omega/release-v1",
            lateKind = "LINK",
            lateContent = "https://docs.example.invalid/omega/release-v4",
        ),
    )

    private fun fixture(
        pairId: String,
        surface: String,
        clockMillis: Long,
        purpose: String,
        target: String,
        e1Kind: String,
        e1Content: String,
        lateKind: String,
        lateContent: String,
    ): JSONObject = JSONObject()
        .put("pair_id", pairId)
        .put("surface", surface)
        .put("virtual_clock_millis", clockMillis)
        .put("purpose", purpose)
        .put("target", target)
        .put("room_display", "합성 검토방 ${target.takeLast(4)}")
        .put("current_role", "합성 내부 검토자")
        .put("allowed_scope", "합성 프로젝트 내부")
        .put("e1_event_id", "$pairId-e1")
        .put("e1_kind", e1Kind)
        .put("e1_content", e1Content)
        .put("e2_a_event_id", "$pairId-e2-a")
        .put("e2_a_content", "https://docs.example.invalid/$purpose/revision-a")
        .put("e2_b_event_id", "$pairId-e2-b")
        .put("e2_b_content", "https://docs.example.invalid/$purpose/revision-b")
        .put("late_event_id", "$pairId-late")
        .put("late_kind", lateKind)
        .put("late_content", lateContent)

    private fun safeNamespace(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "run" }
}
